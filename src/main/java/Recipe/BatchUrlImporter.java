package main.java.Recipe;

import main.java.AI.RecipeAIService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * URLを1行ずつ列挙したテキストファイルを読み込み、各URLをAIに解析させて
 * レシピとして {@link AllRecipeList} へ登録するバッチ処理。
 *
 * - 空行・先頭が `#` のコメント行は無視
 * - 入力ファイル内で重複している URL は1回しか処理しない(順序は保持)
 * - 既に DB に登録済みの URL は AI 呼び出しをスキップ(無駄なAPIコール抑止)
 * - 1件ずつ順次AI呼び出し(レート制限への配慮 + 進捗表示しやすさ)
 * - タイトル/食材どちらも検出できなかったURLはスキップ(失敗カウント)
 * - 例外を投げるAPIエラーは握りつぶしてコンソールに出力し、後続URLの処理を続行
 *
 * UI 側からは {@link #importAll} を非同期(バックグラウンドスレッド)で呼び出す想定。
 */
public final class BatchUrlImporter {

    /** 1件あたりの処理状態 */
    public enum Status {
        /** AIへ問い合わせ中 */
        PROCESSING,
        /** 登録に成功した */
        SUCCEEDED,
        /** AI例外/検出失敗で登録できなかった */
        FAILED,
        /** URL重複(DB既登録 or 入力内重複)で AI を呼ばずにスキップ */
        SKIPPED_DUPLICATE
    }

    /** 1件のバッチ処理結果 */
    public static final class Result {
        /** 登録に成功した件数 */
        public final int succeeded;
        /** 失敗または検出できなかった件数 */
        public final int failed;
        /** URL重複でスキップした件数(DB既登録) */
        public final int skipped;
        /** 入力に含まれていた URL 総数(空行・コメント・ファイル内重複を除く) */
        public final int total;

        public Result(int succeeded, int failed, int skipped, int total) {
            this.succeeded = succeeded;
            this.failed = failed;
            this.skipped = skipped;
            this.total = total;
        }
    }

    /** 進捗通知用イベント。Swing側で進捗バー更新やステータス表示に利用する */
    public static final class Progress {
        /** 0始まりの処理中インデックス */
        public final int index;
        /** 全体URL数 */
        public final int total;
        /** 現在処理中の URL */
        public final String url;
        /** 1件あたりの状態 */
        public final Status status;

        public Progress(int index, int total, String url, Status status) {
            this.index = index;
            this.total = total;
            this.url = url;
            this.status = status;
        }
    }

    private BatchUrlImporter() {}

    /**
     * 指定ファイルから URL を読み込み、空行・コメント(#始まり)・重複を排除して返す。
     * 同じ URL が複数行に出現しても1件としてだけ処理される(順序は最初の出現位置を維持)。
     * @param file 入力ファイル
     * @return 重複排除済みURLリスト(順序保持)
     * @throws IOException 読み込み失敗時
     */
    public static List<String> readUrls(Path file) throws IOException {
        return readUrls(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    /**
     * 行のリストから URL を抽出し、空行・コメント(#始まり)・重複を排除して返す。
     * Android の Storage Access Framework 経由(ストリーム読み込み)でも利用できるよう、
     * ファイルパスに依存しないオーバーロードとして提供する。
     * @param lines 入力テキストの行リスト
     * @return 重複排除済みURLリスト(順序保持)
     */
    public static List<String> readUrls(List<String> lines) {
        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        for (String line : lines) {
            String trimmed = line.replace("﻿", "").trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            uniq.add(trimmed);
        }
        return new ArrayList<>(uniq);
    }

    /**
     * URLリストを順次処理してレシピとして登録する(同期)。
     * 既に DB に同じ URL のレシピが登録されている場合は AI 呼び出しをスキップする。
     * @param urls         処理対象URLリスト(重複排除済み想定。{@link #readUrls(Path)} の返り値を渡す)
     * @param aiService    設定済みのAIサービス(setConfig済みであること)
     * @param master       食材マスタ(未知の食材は OTHER で自動追加)
     * @param recipeList   登録先のレシピリスト
     * @param onProgress   進捗通知コールバック(null可)
     * @param cancelCheck  キャンセル要求の問い合わせ(null可、true を返したら処理中止)
     * @return 集計結果
     */
    public static Result importAll(List<String> urls,
                                   RecipeAIService aiService,
                                   IngredientMaster master,
                                   AllRecipeList recipeList,
                                   Consumer<Progress> onProgress,
                                   BooleanSupplier cancelCheck) {
        // 開始時点で DB に登録済みの URL を集めておき、AI を呼び出さずにスキップ判定する。
        // バッチ実行中の追加分も逐次セットへ反映するので、入力に同じURLが残っていても二重登録されない。
        Set<String> existingUrls = new HashSet<>();
        for (Recipe r : recipeList.getRecipeList()) {
            if (r.getUrl() != null && !r.getUrl().isEmpty()) existingUrls.add(r.getUrl());
        }

        int succeeded = 0;
        int failed = 0;
        int skipped = 0;

        for (int i = 0; i < urls.size(); i++) {
            if (cancelCheck != null && cancelCheck.getAsBoolean()) break;
            String url = urls.get(i);

            // === URL重複チェック: 既登録なら AI を呼ばずにスキップ ===
            if (existingUrls.contains(url)) {
                skipped++;
                System.out.println("[BatchUrlImporter] 既登録のためスキップ: " + url);
                if (onProgress != null) {
                    onProgress.accept(new Progress(i, urls.size(), url, Status.SKIPPED_DUPLICATE));
                }
                continue;
            }

            // 処理中通知
            if (onProgress != null) {
                onProgress.accept(new Progress(i, urls.size(), url, Status.PROCESSING));
            }

            Status finalStatus = Status.FAILED;
            try {
                String[] result = aiService.suggestRecipe(url, master.getAllIngredients());
                Recipe recipe = buildRecipe(result, url, master);
                if (recipe != null) {
                    recipeList.addRecipe(recipe);
                    existingUrls.add(url); // 後続でこのURLが再出現しても二重登録されないよう即時反映
                    finalStatus = Status.SUCCEEDED;
                } else {
                    System.err.println("[BatchUrlImporter] レシピ検出失敗(タイトルor食材0件): " + url);
                }
            } catch (Exception ex) {
                System.err.println("[BatchUrlImporter] " + url + " => " + ex.getMessage());
            }

            if (finalStatus == Status.SUCCEEDED) succeeded++;
            else failed++;

            // 結果通知(UI側で進捗バーやステータス表示の更新に利用)
            if (onProgress != null) {
                onProgress.accept(new Progress(i, urls.size(), url, finalStatus));
            }
        }
        return new Result(succeeded, failed, skipped, urls.size());
    }

    /**
     * AIの返却値 {@code [title, "ing1,ing2,..."]} と URL から Recipe を組み立てる。
     * 食材はマスタ存在チェック → 無ければ OTHER カテゴリで自動追加。
     * タイトル空 or 食材0件のときは null を返し、UI/呼び出し側で「検出失敗」扱いにする。
     * カテゴリーは AI が返さないので {@link RecipeCategory#OTHER} 固定。
     * @param result AIからの戻り値(2要素配列)
     * @param url    元のURL(Recipeに格納)
     * @param master 食材マスタ
     * @return 構築された Recipe。検出失敗時は null
     */
    private static Recipe buildRecipe(String[] result, String url, IngredientMaster master) {
        String title = (result == null || result.length < 1 || result[0] == null) ? "" : result[0].trim();
        String ingsRaw = (result == null || result.length < 2 || result[1] == null) ? "" : result[1];

        ArrayList<Ingredient> matched = new ArrayList<>();
        for (String aiIngName : ingsRaw.split(",")) {
            String cleanName = aiIngName.trim();
            if (cleanName.isEmpty()) continue;
            Ingredient resolved = null;
            for (Ingredient ing : master.getAllIngredients()) {
                if (ing.getName().equals(cleanName)) {
                    resolved = ing;
                    break;
                }
            }
            if (resolved == null) {
                resolved = master.addIngredient(cleanName);
            }
            if (resolved != null && !matched.contains(resolved)) {
                matched.add(resolved);
            }
        }

        if (title.isEmpty() || matched.isEmpty()) return null;
        return new Recipe(title, url, matched, EnumSet.of(RecipeCategory.OTHER));
    }
}
