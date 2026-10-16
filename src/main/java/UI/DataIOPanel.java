package main.java.UI;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import main.java.MainActivity;
import main.java.Recipe.BatchUrlImporter;
import main.java.Recipe.Recipe;
import main.java.Recipe.RecipeIO;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 保存済みレシピをJSONファイルへ書き出す/読み込む画面。
 * バックアップや別端末への移行に利用する。
 * Android ではファイル選択に Storage Access Framework (SAF) を使用する。
 */
public class DataIOPanel extends ScrollView {

    private final MainActivity owner;

    /**
     * データ入出力パネルを構築する。
     * @param owner 共有状態(レシピリスト・食材マスタ)にアクセスするためのアクティビティ参照
     */
    public DataIOPanel(MainActivity owner) {
        super(owner);
        this.owner = owner;

        LinearLayout form = new LinearLayout(owner);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = UIComponents.dp(owner, 16);
        form.setPadding(pad, pad, pad, pad);
        addView(form, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        form.addView(UIComponents.createTitleLabel(owner, "データ入出力"));
        UIComponents.addSpacer(form, 20);

        // ===== エクスポート =====
        form.addView(UIComponents.createHeaderLabel(owner, "エクスポート"));
        form.addView(UIComponents.createLabel(owner,
                "現在保存されている全レシピを JSON ファイルに書き出します。"
                + "別端末(PC版を含む)への移行やバックアップに利用できます。"));
        UIComponents.addSpacer(form, 8);
        Button btnExport = UIComponents.createPrimaryButton(owner, "レシピをファイルに書き出す…");
        btnExport.setOnClickListener(v -> doExport());
        form.addView(btnExport);
        UIComponents.addSpacer(form, 24);

        // ===== インポート =====
        form.addView(UIComponents.createHeaderLabel(owner, "インポート"));
        form.addView(UIComponents.createLabel(owner,
                "JSON ファイルからレシピを読み込み、現在のデータベースに追加します。"
                + "既存のレシピは削除されません(同名レシピも追加されます)。"));
        UIComponents.addSpacer(form, 8);
        Button btnImport = UIComponents.createPrimaryButton(owner, "ファイルからレシピを読み込む…");
        btnImport.setOnClickListener(v -> doImport());
        form.addView(btnImport);
        UIComponents.addSpacer(form, 24);

        // ===== URLバッチ取込み (AI) =====
        form.addView(UIComponents.createHeaderLabel(owner, "URLバッチ取込み (AI)"));
        form.addView(UIComponents.createLabel(owner,
                "1行に1つの URL を記述したテキスト(.txt)ファイルを選び、各 URL を"
                + "現在選択中の AI プロバイダー(設定画面で選択中のもの)で順次解析して"
                + "一括登録します。空行と # から始まるコメント行は無視されます。"
                + "カテゴリーも AI が選びます(選べなかった場合は「その他」)。必要に応じて後で編集してください。"));
        UIComponents.addSpacer(form, 8);
        Button btnBatch = UIComponents.createPrimaryButton(owner, "URLリストから一括登録…");
        btnBatch.setOnClickListener(v -> doBatchUrlImport());
        form.addView(btnBatch);
        UIComponents.addSpacer(form, 24);

        // ===== 現状サマリー =====
        int count = owner.getAllRecipeList().getRecipeList().size();
        form.addView(UIComponents.createLabel(owner, "現在登録されているレシピ: " + count + "件"));
    }

    /**
     * 指定 Uri の内容を UTF-8 テキストとして1行ずつ読み込む。
     * @param uri 読み込み対象(SAFで選択されたファイル)
     * @return ファイルの各行
     * @throws Exception 読み込み失敗時
     */
    private List<String> readLines(Uri uri) throws Exception {
        List<String> lines = new ArrayList<>();
        try (InputStream in = owner.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("ファイルを開けません: " + uri);
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
        }
        return lines;
    }

    /**
     * エクスポート処理: SAFで保存先を選択 → JSON書き出し。
     */
    private void doExport() {
        List<Recipe> recipes = owner.getAllRecipeList().getRecipeList();
        if (recipes.isEmpty()) {
            UIComponents.info(owner, "情報", "エクスポートできるレシピがありません。");
            return;
        }

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "recipes-export.json");

        // SAF の保存画面で上書きの確認・ファイル名の指定ができるため、デスクトップ版の上書き確認は不要
        owner.pickFile(intent, uri -> {
            try (OutputStream out = owner.getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new Exception("ファイルを開けません: " + uri);
                out.write(RecipeIO.exportToJson(recipes).getBytes(StandardCharsets.UTF_8));
                UIComponents.info(owner, "エクスポート完了", recipes.size() + "件のレシピを書き出しました。");
            } catch (Exception ex) {
                UIComponents.info(owner, "エラー", "書き出しに失敗しました: " + ex.getMessage());
                ex.printStackTrace();
            }
        });
    }

    /**
     * インポート処理: SAFでファイル選択 → JSON読み込み → AllRecipeList に追加。
     */
    private void doImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/json", "text/plain", "application/octet-stream"});

        owner.pickFile(intent, uri -> {
            try {
                String json = String.join("\n", readLines(uri));
                List<Recipe> imported = RecipeIO.parseFromJson(json, owner.getIngredientMaster());
                if (imported.isEmpty()) {
                    UIComponents.info(owner, "情報", "ファイルから有効なレシピが見つかりませんでした。");
                    return;
                }
                UIComponents.confirm(owner, "インポート確認",
                        imported.size() + "件のレシピが見つかりました。追加しますか?",
                        () -> {
                            int added = 0;
                            for (Recipe r : imported) {
                                owner.getAllRecipeList().addRecipe(r);
                                added++;
                            }
                            UIComponents.info(owner, "インポート完了", added + "件のレシピを追加しました。");
                            owner.showWelcome();
                        });
            } catch (Exception ex) {
                UIComponents.info(owner, "エラー", "読み込みに失敗しました: " + ex.getMessage());
                ex.printStackTrace();
            }
        });
    }

    /**
     * URLバッチ取込み: SAFでテキストファイル選択 → 各URLをAIに解析させて順次登録。
     * AI呼び出しは時間がかかるため進捗ダイアログを表示し、キャンセル可能にする。
     */
    private void doBatchUrlImport() {
        // 1. AI 設定チェック (ローカルLLM以外は APIキー必須)
        if (!UIComponents.ensureAiReady(owner, owner.getAiSettings())) return;

        // 2. ファイル選択
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");

        owner.pickFile(intent, uri -> {
            // 3. URL 読み込み
            final List<String> urls;
            try {
                urls = BatchUrlImporter.readUrls(readLines(uri));
            } catch (Exception ex) {
                UIComponents.info(owner, "エラー", "ファイル読み込みに失敗しました: " + ex.getMessage());
                return;
            }
            if (urls.isEmpty()) {
                UIComponents.info(owner, "情報", "ファイル内に有効なURLが見つかりませんでした。");
                return;
            }

            // 4. 確認
            UIComponents.confirm(owner, "一括登録の確認",
                    urls.size() + " 件のURLをAIで解析して一括登録します。\n"
                    + "(1件あたり10〜60秒程度かかります)\n\n続行しますか？",
                    () -> runBatchImport(urls));
        });
    }

    /**
     * 進捗ダイアログを表示しつつ {@link BatchUrlImporter#importAll} をバックグラウンド実行する。
     * @param urls 処理対象URLリスト
     */
    private void runBatchImport(List<String> urls) {
        LinearLayout content = new LinearLayout(owner);
        content.setOrientation(LinearLayout.VERTICAL);
        int p = UIComponents.dp(owner, 24);
        content.setPadding(p, p, p, p);

        TextView statusLabel = UIComponents.createLabel(owner, "準備中...");
        content.addView(statusLabel);
        UIComponents.addSpacer(content, 12);
        ProgressBar bar = new ProgressBar(owner, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(urls.size());
        content.addView(bar);

        // ユーザーがキャンセルしたかをバックグラウンド側に伝えるフラグ
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        AlertDialog dialog = new AlertDialog.Builder(owner)
                .setTitle("AIで一括登録中…")
                .setView(content)
                .setCancelable(false)
                .setNegativeButton("キャンセル", null)
                .create();
        dialog.show();
        // ボタン押下で自動的に閉じないよう、表示後にリスナーを差し替える
        Button cancelBtn = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        cancelBtn.setOnClickListener(v -> {
            cancelled.set(true);
            cancelBtn.setEnabled(false);
            statusLabel.setText("キャンセル中... (現在のリクエスト完了を待機)");
        });

        UIComponents.runAsync(
                () -> BatchUrlImporter.importAll(urls,
                        owner.getAiSettings().createService(),
                        owner.getIngredientMaster(),
                        owner.getAllRecipeList(),
                        progress -> UIComponents.postToMain(() -> showProgress(progress, bar, statusLabel)),
                        cancelled::get),
                result -> {
                    dialog.dismiss();
                    String msg = (cancelled.get() ? "キャンセルされました。\n" : "一括登録が完了しました。\n")
                            + "成功: " + result.succeeded + "件\n"
                            + "失敗: " + result.failed + "件\n"
                            + "スキップ(URL重複): " + result.skipped + "件";
                    UIComponents.info(owner, "結果", msg);
                    owner.showWelcome();
                },
                ex -> {
                    dialog.dismiss();
                    UIComponents.info(owner, "エラー", "一括登録に失敗しました: " + ex.getMessage());
                    ex.printStackTrace();
                });
    }

    /**
     * 進捗を進捗バーと状態表示に反映する。
     * @param progress    進捗
     * @param bar         進捗バー
     * @param statusLabel 状態表示
     */
    private static void showProgress(BatchUrlImporter.Progress progress, ProgressBar bar, TextView statusLabel) {
        // 進捗バー: PROCESSING ならカレントの開始位置、それ以外(成功/失敗/スキップ)は1件分加算
        bar.setProgress(progress.index + (progress.status == BatchUrlImporter.Status.PROCESSING ? 0 : 1));
        String stage;
        switch (progress.status) {
            case PROCESSING:        stage = "解析中"; break;
            case SUCCEEDED:         stage = "登録完了"; break;
            case SKIPPED_DUPLICATE: stage = "スキップ(URL重複)"; break;
            case FAILED:
            default:                stage = "失敗"; break;
        }
        statusLabel.setText("[" + (progress.index + 1) + "/" + progress.total + "] " + stage + ": " + progress.url);
    }
}
