package main.java.UI;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import main.java.MainActivity;
import main.java.AI.RecipeAIService;
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
        addSpacer(form, 20);

        // ===== エクスポート =====
        form.addView(UIComponents.createTitleLabel(owner, "エクスポート"));
        form.addView(UIComponents.createLabel(owner,
                "現在保存されている全レシピを JSON ファイルに書き出します。"
                + "別端末への移行やバックアップに利用できます。"));
        addSpacer(form, 8);
        Button btnExport = UIComponents.createPrimaryButton(owner, "レシピをファイルに書き出す…");
        btnExport.setOnClickListener(v -> doExport());
        form.addView(btnExport);
        addSpacer(form, 24);

        // ===== インポート =====
        form.addView(UIComponents.createTitleLabel(owner, "インポート"));
        form.addView(UIComponents.createLabel(owner,
                "JSON ファイルからレシピを読み込み、現在のデータベースに追加します。"
                + "既存のレシピは削除されません(同名レシピも追加されます)。"));
        addSpacer(form, 8);
        Button btnImport = UIComponents.createPrimaryButton(owner, "ファイルからレシピを読み込む…");
        btnImport.setOnClickListener(v -> doImport());
        form.addView(btnImport);
        addSpacer(form, 24);

        // ===== URLバッチ取込み (AI) =====
        form.addView(UIComponents.createTitleLabel(owner, "URLバッチ取込み (AI)"));
        form.addView(UIComponents.createLabel(owner,
                "1行に1つの URL を記述したテキスト(.txt)ファイルを選び、各 URL を"
                + "現在選択中の AI プロバイダー(設定画面で選択中のもの)で順次解析して"
                + "一括登録します。空行と # から始まるコメント行は無視されます。"
                + "カテゴリーは「その他」固定で登録されるため、必要に応じて後で編集してください。"));
        addSpacer(form, 8);
        Button btnBatch = UIComponents.createPrimaryButton(owner, "URLリストから一括登録…");
        btnBatch.setOnClickListener(v -> doBatchUrlImport());
        form.addView(btnBatch);
        addSpacer(form, 24);

        // ===== 現状サマリー =====
        int count = owner.getAllRecipeList().getRecipeList().size();
        form.addView(UIComponents.createLabel(owner, "現在登録されているレシピ: " + count + "件"));
    }

    /**
     * 縦方向スペーサを追加する。
     * @param parent   追加先
     * @param heightDp スペーサの高さ(dp)
     */
    private void addSpacer(LinearLayout parent, int heightDp) {
        View spacer = new View(owner);
        parent.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UIComponents.dp(owner, heightDp)));
    }

    /**
     * 指定 Uri の内容を UTF-8 テキストとして読み切る。
     * @param uri 読み込み対象(SAFで選択されたファイル)
     * @return ファイル内容の文字列
     * @throws Exception 読み込み失敗時
     */
    private String readTextFromUri(Uri uri) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = owner.getContentResolver().openInputStream(uri);
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
        }
        return sb.toString();
    }

    /**
     * エクスポート処理: SAFで保存先を選択 → JSON書き出し。
     */
    private void doExport() {
        List<Recipe> recipes = owner.getAllRecipeList().getRecipeList();
        if (recipes.isEmpty()) {
            UIComponents.info(owner, "エクスポートできるレシピがありません。");
            return;
        }

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "recipes-export.json");

        owner.pickFile(intent, uri -> {
            try (OutputStream out = owner.getContentResolver().openOutputStream(uri, "wt")) {
                String json = RecipeIO.exportToJson(recipes);
                out.write(json.getBytes(StandardCharsets.UTF_8));
                UIComponents.info(owner, "エクスポート完了",
                        recipes.size() + "件のレシピを書き出しました。");
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
                String json = readTextFromUri(uri);
                List<Recipe> imported = RecipeIO.parseFromJson(json, owner.getIngredientMaster());
                if (imported.isEmpty()) {
                    UIComponents.info(owner, "ファイルから有効なレシピが見つかりませんでした。");
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
        // 1. AI 設定チェック (Ollama 以外は APIキー必須)
        if (owner.getCurrentAiProvider() != RecipeAIService.Provider.OLLAMA
                && owner.getCurrentApiKey().isEmpty()) {
            UIComponents.info(owner, "AI未設定",
                    "先に「AI設定」メニューでプロバイダーとAPIキーを設定してください。");
            return;
        }

        // 2. ファイル選択
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");

        owner.pickFile(intent, uri -> {
            // 3. URL 読み込み
            final List<String> urls;
            try {
                List<String> lines = new ArrayList<>();
                for (String line : readTextFromUri(uri).split("\n", -1)) lines.add(line);
                urls = BatchUrlImporter.readUrls(lines);
            } catch (Exception ex) {
                UIComponents.info(owner, "エラー", "ファイル読み込みに失敗しました: " + ex.getMessage());
                return;
            }
            if (urls.isEmpty()) {
                UIComponents.info(owner, "ファイル内に有効なURLが見つかりませんでした。");
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
        addSpacer(content, 12);
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
                () -> {
                    RecipeAIService aiService = new RecipeAIService();
                    aiService.setConfig(owner.getCurrentAiProvider(),
                            owner.getCurrentApiKey(),
                            owner.getCurrentModelName());
                    return BatchUrlImporter.importAll(urls,
                            aiService,
                            owner.getIngredientMaster(),
                            owner.getAllRecipeList(),
                            progress -> UIComponents.postToMain(() -> {
                                // 進捗バー: PROCESSING ならカレントの開始位置、それ以外(成功/失敗/スキップ)は1件分加算
                                int progressed = progress.index
                                        + (progress.status == BatchUrlImporter.Status.PROCESSING ? 0 : 1);
                                bar.setProgress(progressed);
                                String stage;
                                switch (progress.status) {
                                    case PROCESSING:        stage = "解析中"; break;
                                    case SUCCEEDED:         stage = "登録完了"; break;
                                    case SKIPPED_DUPLICATE: stage = "スキップ(URL重複)"; break;
                                    case FAILED:
                                    default:                stage = "失敗"; break;
                                }
                                statusLabel.setText("[" + (progress.index + 1) + "/" + progress.total + "] "
                                        + stage + ": " + progress.url);
                            }),
                            cancelled::get);
                },
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
}
