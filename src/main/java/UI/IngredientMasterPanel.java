package main.java.UI;

import android.app.AlertDialog;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import main.java.MainActivity;
import main.java.Recipe.Ingredient;
import main.java.Recipe.IngredientCategory;
import main.java.Recipe.IngredientNormalizer;

import java.util.ArrayList;
import java.util.List;

/**
 * 登録済み食材一覧を閲覧・編集する画面パネル。
 * 行のカテゴリーをタップで変更でき、↑/↓ボタンで並び順を入れ替えできる。
 * 「変更を保存」を押すと、現在の一覧内容を IngredientMaster 経由で
 * database.csv に上書き保存する。
 * (デスクトップ版 JTable ベースのパネルの Android View 置き換え)
 */
public class IngredientMasterPanel extends LinearLayout {

    private final MainActivity owner;
    /** 編集中の作業コピー(保存時に replaceAll でマスタへ反映) */
    private final ArrayList<Ingredient> working = new ArrayList<>();
    private final IngredientAdapter adapter;

    /**
     * 食材管理パネルを構築する。
     * @param owner 共有状態(IngredientMaster)にアクセスするためのアクティビティ参照
     */
    public IngredientMasterPanel(MainActivity owner) {
        super(owner);
        this.owner = owner;
        setOrientation(VERTICAL);
        int pad = UIComponents.dp(owner, 16);
        setPadding(pad, pad, pad, pad);

        // ===== ヘッダー(タイトル + 表記ゆれをAIで統一するボタン) =====
        LinearLayout titleRow = new LinearLayout(owner);
        titleRow.setOrientation(HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(UIComponents.createTitleLabel(owner, "食材管理"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button btnUnify = UIComponents.createAiButton(owner, "✨ 表記ゆれをAIで統一");
        btnUnify.setOnClickListener(v -> detectAndUnifyVariants());
        titleRow.addView(btnUnify);
        addView(titleRow);

        TextView hint = UIComponents.createLabel(owner,
                "食材名をタップするとカテゴリーを変更できます。"
                + "↑/↓で並び順を変更できます。"
                + "「変更を保存」を押すと database.csv に書き戻します。");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        addView(hint);

        // ===== 一覧 =====
        working.addAll(owner.getIngredientMaster().getAllIngredients());
        adapter = new IngredientAdapter();
        ListView list = new ListView(owner);
        list.setAdapter(adapter);
        addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ===== 保存ボタン =====
        Button btnSave = UIComponents.createPrimaryButton(owner, "変更を保存");
        btnSave.setOnClickListener(v -> save());
        addView(btnSave, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 食材1行(番号 + 食材名 + カテゴリー + ↑/↓ボタン)を表示するアダプタ */
    private final class IngredientAdapter extends BaseAdapter {

        @Override
        public int getCount() { return working.size(); }

        @Override
        public Object getItem(int position) { return working.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Ingredient ing = working.get(position);

            LinearLayout row = new LinearLayout(owner);
            row.setOrientation(HORIZONTAL);
            int p = UIComponents.dp(owner, 4);
            row.setPadding(p, p, p, p);
            row.setGravity(Gravity.CENTER_VERTICAL);

            // 番号 + 食材名 + カテゴリー(タップでカテゴリー変更)
            TextView label = UIComponents.createLabel(owner,
                    (position + 1) + ". " + ing.getName() + "  [" + ing.getCategory().name() + "]");
            label.setOnClickListener(v -> showCategoryPicker(position));
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            row.addView(createMoveButton("↑", position, -1));
            row.addView(createMoveButton("↓", position, 1));
            return row;
        }

        /**
         * 行を上下に移動するボタンを生成する。
         * @param text     ボタン表示
         * @param position 対象行
         * @param delta    -1=上、+1=下
         * @return ボタン
         */
        private Button createMoveButton(String text, int position, int delta) {
            Button b = UIComponents.createButton(owner, text);
            b.setMinWidth(UIComponents.dp(owner, 44));
            b.setMinimumWidth(UIComponents.dp(owner, 44));
            b.setOnClickListener(v -> move(position, delta));
            return b;
        }
    }

    /**
     * 指定行を上または下へ1行分移動する。端にある場合は何もしない。
     * @param position 対象行
     * @param delta    -1=上、+1=下
     */
    private void move(int position, int delta) {
        int target = position + delta;
        if (target < 0 || target >= working.size()) return;
        Ingredient tmp = working.get(position);
        working.set(position, working.get(target));
        working.set(target, tmp);
        adapter.notifyDataSetChanged();
    }

    /**
     * カテゴリー変更ダイアログ(単一選択)を表示し、選択されたカテゴリーで行を置き換える。
     * @param position 対象行
     */
    private void showCategoryPicker(int position) {
        Ingredient ing = working.get(position);
        IngredientCategory[] cats = IngredientCategory.values();
        String[] names = new String[cats.length];
        int current = 0;
        for (int i = 0; i < cats.length; i++) {
            names[i] = cats[i].name();
            if (cats[i] == ing.getCategory()) current = i;
        }
        new AlertDialog.Builder(owner)
                .setTitle("「" + ing.getName() + "」のカテゴリー")
                .setSingleChoiceItems(names, current, (d, which) -> {
                    working.set(position, new Ingredient(ing.getName(), cats[which]));
                    adapter.notifyDataSetChanged();
                    d.dismiss();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    /**
     * AIに食材名の表記ゆれを検出させ、確認のうえマスタとレシピへ統一を適用する。
     * AIの呼び出しは進捗ダイアログ付きでバックグラウンド実行する。
     */
    private void detectAndUnifyVariants() {
        // ローカルLLM(Ollama / llama.cpp)以外は APIキー必須
        if (!UIComponents.ensureAiReady(owner, owner.getAiSettings())) return;

        List<String> names = owner.getIngredientMaster().getAllIngredientNames();
        if (names.size() < 2) {
            UIComponents.info(owner, "情報", "食材が2件未満のため、表記ゆれ検出をスキップしました。");
            return;
        }

        // 進捗ダイアログ(不確定バー)
        LinearLayout content = new LinearLayout(owner);
        content.setOrientation(VERTICAL);
        int p = UIComponents.dp(owner, 24);
        content.setPadding(p, p, p, p);
        content.addView(UIComponents.createLabel(owner, "食材名を解析しています。しばらくお待ちください…"));
        content.addView(new ProgressBar(owner));
        AlertDialog dialog = new AlertDialog.Builder(owner)
                .setTitle("AIが表記ゆれを解析中…")
                .setView(content)
                .setCancelable(false)
                .create();
        dialog.show();

        UIComponents.runAsync(
                () -> IngredientNormalizer.detectVariants(owner.getAiSettings().createService(), names),
                groups -> {
                    dialog.dismiss();
                    handleDetectedGroups(groups);
                },
                ex -> {
                    dialog.dismiss();
                    UIComponents.info(owner, "エラー",
                            "AIの呼び出しに失敗しました。\n設定内容を確認してください。\n\n" + ex.getMessage());
                    ex.printStackTrace();
                });
    }

    /**
     * AIが検出したグループを現在のマスタと突き合わせて確認ダイアログを出し、
     * ユーザーがOKしたら統一を適用して一覧を再読込する。
     * @param groups AIが返した表記ゆれグループ
     */
    private void handleDetectedGroups(List<IngredientNormalizer.VariantGroup> groups) {
        // 現在マスタに実在する名前だけに絞り込んでから確認画面に表示する
        List<IngredientNormalizer.VariantGroup> effective =
                IngredientNormalizer.filterExisting(groups, owner.getIngredientMaster());

        if (effective.isEmpty()) {
            UIComponents.info(owner, "検出結果", "表記ゆれは見つかりませんでした。");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("以下の表記ゆれが見つかりました。統一しますか？\n");
        sb.append("(食材マスタと、その食材を使っている全レシピを書き換えます)\n\n");
        for (IngredientNormalizer.VariantGroup g : effective) {
            sb.append(String.join("・", g.variants)).append("  →  ").append(g.canonical).append("\n");
        }

        UIComponents.confirm(owner, "統一の確認", sb.toString(), () -> {
            IngredientNormalizer.ApplyResult result = IngredientNormalizer.apply(
                    effective, owner.getIngredientMaster(), owner.getAllRecipeList());

            reloadList();
            UIComponents.info(owner, "完了",
                    "統一が完了しました。\n"
                    + "統合した別表記: " + result.mergedNames + "件\n"
                    + "更新したレシピ: " + result.updatedRecipes + "件");
        });
    }

    /**
     * 一覧の内容を現在の食材マスタから再読込する(統一適用後の反映用)。
     */
    private void reloadList() {
        working.clear();
        working.addAll(owner.getIngredientMaster().getAllIngredients());
        adapter.notifyDataSetChanged();
    }

    /**
     * 現在の一覧内容を IngredientMaster.replaceAll に渡して database.csv に保存する。
     */
    private void save() {
        List<Ingredient> ordered = new ArrayList<>(working);
        owner.getIngredientMaster().replaceAll(ordered);
        UIComponents.info(owner, "保存完了", ordered.size() + " 件の食材を保存しました。");
        owner.showWelcome();
    }
}
