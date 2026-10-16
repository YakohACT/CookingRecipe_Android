package main.java.UI;

import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import main.java.MainActivity;
import main.java.AI.RecipeSuggestion;
import main.java.Recipe.BatchUrlImporter;
import main.java.Recipe.Ingredient;
import main.java.Recipe.IngredientCategory;
import main.java.Recipe.Quantity;
import main.java.Recipe.Recipe;
import main.java.Recipe.RecipeCategory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * レシピ登録/編集画面パネル（AI自動提案機能付き）。
 * editTarget が null なら新規登録、非nullなら既存レシピの更新として動作する
 * 使用する食材は一覧で管理し、食材ごとに数量と単位(分量)を入力できる
 * (デスクトップ版 Swing パネルの Android View 置き換え。JTable の行は1行ずつの入力欄にしている)
 */
public class RegisterRecipePanel extends ScrollView {

    private static final String AI_BUTTON_LABEL = "✨ AI自動提案";

    private final MainActivity owner;
    private final Recipe editTarget;
    private final EditText titleField;
    private final EditText urlField;
    /** RecipeCategory.values() と同じ並びのチェックボックス */
    private final CheckBox[] categoryChecks = new CheckBox[RecipeCategory.values().length];
    /** 使用する食材の行(食材 / 数量 / 単位) */
    private final List<IngredientRow> rows = new ArrayList<>();
    private final LinearLayout rowsContainer;

    /** 使用する食材1行分の入力欄 */
    private static final class IngredientRow {
        final Ingredient ingredient;
        final EditText amountField;
        final AutoCompleteTextView unitField;
        final View root;

        IngredientRow(Ingredient ingredient, EditText amountField, AutoCompleteTextView unitField, View root) {
            this.ingredient = ingredient;
            this.amountField = amountField;
            this.unitField = unitField;
            this.root = root;
        }
    }

    /**
     * 新規登録モードで構築する。
     * @param owner 共有状態にアクセスするためのアクティビティ参照
     */
    public RegisterRecipePanel(MainActivity owner) {
        this(owner, null);
    }

    /**
     * 編集モード(または editTarget=null で新規登録モード)で構築する。
     * editTarget が非nullなら、フォームに既存値が prefill され保存ボタンが「更新」になる。
     * @param owner      共有状態にアクセスするためのアクティビティ参照
     * @param editTarget 編集対象レシピ。null なら新規登録モード
     */
    public RegisterRecipePanel(MainActivity owner, Recipe editTarget) {
        super(owner);
        this.owner = owner;
        this.editTarget = editTarget;

        LinearLayout form = new LinearLayout(owner);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = UIComponents.dp(owner, 16);
        form.setPadding(pad, pad, pad, pad);
        addView(form, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button btnAi = UIComponents.createAiButton(owner, AI_BUTTON_LABEL);
        form.addView(buildHeader(btnAi));
        UIComponents.addSpacer(form, 12);

        // ===== タイトル / URL =====
        form.addView(UIComponents.createLabel(owner, "タイトル:"));
        titleField = UIComponents.createStyledTextField(owner);
        form.addView(titleField);
        UIComponents.addSpacer(form, 8);
        form.addView(UIComponents.createLabel(owner, "URL:"));
        urlField = UIComponents.createStyledTextField(owner);
        urlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(urlField);
        UIComponents.addSpacer(form, 8);

        // ===== レシピのカテゴリー(複数選択チェックボックス) =====
        form.addView(UIComponents.createLabel(owner, "レシピのカテゴリー (複数選択可):"));
        form.addView(buildCategoryGrid());
        UIComponents.addSpacer(form, 8);

        // ===== 食材選択(カテゴリ → 食材 → 追加ボタン) =====
        form.addView(UIComponents.createLabel(owner, "カテゴリから食材を選択:"));
        Spinner catSpinner = new Spinner(owner);
        catSpinner.setAdapter(UIComponents.createSpinnerAdapter(owner, IngredientCategory.values()));
        form.addView(catSpinner);
        Spinner ingSpinner = new Spinner(owner);
        form.addView(ingSpinner);
        catSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                IngredientCategory cat = (IngredientCategory) parent.getItemAtPosition(position);
                ingSpinner.setAdapter(UIComponents.createSpinnerAdapter(
                        owner, owner.getIngredientMaster().searchIngredient(cat)));
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        Button btnAdd = UIComponents.createButton(owner, "選択した食材を追加");
        btnAdd.setOnClickListener(v -> {
            Object selected = ingSpinner.getSelectedItem();
            if (selected instanceof Ingredient) addIngredientRow((Ingredient) selected, Quantity.EMPTY);
        });
        form.addView(btnAdd);
        UIComponents.addSpacer(form, 16);

        // ===== 使用する食材と分量 =====
        form.addView(UIComponents.createLabel(owner,
                "使用する食材と分量 (数量を入力し、単位は候補から選ぶか直接入力。✕で解除):"));
        rowsContainer = new LinearLayout(owner);
        rowsContainer.setOrientation(LinearLayout.VERTICAL);
        form.addView(rowsContainer);
        UIComponents.addSpacer(form, 16);

        btnAi.setOnClickListener(v -> requestAiSuggestion(btnAi));

        Button btnSubmit = UIComponents.createPrimaryButton(owner, editTarget == null ? "レシピを保存" : "レシピを更新");
        btnSubmit.setOnClickListener(v -> submitRecipe());
        form.addView(btnSubmit);

        // 編集モードのときは既存レシピの内容をフォームに復元
        if (editTarget != null) {
            prefillFromRecipe(editTarget);
        } else {
            refreshEmptyMessage();
        }
    }

    /**
     * フォームヘッダー(タイトル文字列 + AIボタン)を構築する。
     * @param btnAi AI自動提案ボタン
     * @return ヘッダー
     */
    private LinearLayout buildHeader(Button btnAi) {
        LinearLayout header = new LinearLayout(owner);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleLabel = UIComponents.createTitleLabel(owner, editTarget == null ? "新規レシピ登録" : "レシピ編集");
        header.addView(titleLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(btnAi);
        return header;
    }

    /**
     * レシピカテゴリーのチェックボックスを3列で並べる(JList の複数選択の置き換え)。
     * @return チェックボックスのグリッド
     */
    private GridLayout buildCategoryGrid() {
        GridLayout grid = new GridLayout(owner);
        grid.setColumnCount(3);
        RecipeCategory[] cats = RecipeCategory.values();
        for (int i = 0; i < cats.length; i++) {
            CheckBox cb = new CheckBox(owner);
            cb.setText(cats[i].getDisplayName());
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            cb.setLayoutParams(lp);
            categoryChecks[i] = cb;
            grid.addView(cb);
        }
        return grid;
    }

    /**
     * 使用する食材の一覧に1行追加する。既に同じ食材がある場合は何もしない。
     * @param ing      追加する食材
     * @param quantity その食材の分量
     */
    private void addIngredientRow(Ingredient ing, Quantity quantity) {
        for (IngredientRow r : rows) {
            if (r.ingredient.equals(ing)) return;
        }
        LinearLayout row = new LinearLayout(owner);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = UIComponents.createLabel(owner, ing.getName());
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        EditText amount = UIComponents.createStyledTextField(owner);
        amount.setHint("数量");
        amount.setText(quantity.getAmount());
        row.addView(amount, new LinearLayout.LayoutParams(UIComponents.dp(owner, 72), ViewGroup.LayoutParams.WRAP_CONTENT));

        AutoCompleteTextView unit = UIComponents.createUnitField(owner);
        unit.setText(quantity.getUnit(), false);
        row.addView(unit, new LinearLayout.LayoutParams(UIComponents.dp(owner, 100), ViewGroup.LayoutParams.WRAP_CONTENT));

        Button remove = UIComponents.createButton(owner, "✕");
        remove.setMinWidth(0);
        remove.setMinimumWidth(0);
        row.addView(remove, new LinearLayout.LayoutParams(UIComponents.dp(owner, 48), ViewGroup.LayoutParams.WRAP_CONTENT));

        IngredientRow entry = new IngredientRow(ing, amount, unit, row);
        remove.setOnClickListener(v -> {
            rows.remove(entry);
            rowsContainer.removeView(row);
            refreshEmptyMessage();
        });
        rows.add(entry);
        rowsContainer.addView(row);
        refreshEmptyMessage();
    }

    /**
     * 食材が1つも無いときだけ「まだ食材が選択されていません」を表示する。
     */
    private void refreshEmptyMessage() {
        View existing = rowsContainer.findViewWithTag("empty");
        if (rows.isEmpty() && existing == null) {
            TextView empty = UIComponents.createLabel(owner, "(まだ食材が選択されていません)");
            empty.setTextColor(Theme.COLOR_MUTED);
            empty.setTag("empty");
            rowsContainer.addView(empty);
        } else if (!rows.isEmpty() && existing != null) {
            rowsContainer.removeView(existing);
        }
    }

    /**
     * 使用する食材の一覧を指定レシピの食材と分量で置き換える。
     * @param r 表示するレシピ
     */
    private void showIngredients(Recipe r) {
        for (IngredientRow row : rows) rowsContainer.removeView(row.root);
        rows.clear();
        for (Ingredient ing : r.getIngredients()) {
            addIngredientRow(ing, r.getQuantity(ing));
        }
        refreshEmptyMessage();
    }

    /**
     * 編集対象レシピの内容をフォーム要素に流し込む。
     * @param r 編集対象のレシピ
     */
    private void prefillFromRecipe(Recipe r) {
        titleField.setText(r.getTitle());
        urlField.setText(r.getUrl());
        showIngredients(r);
        selectCategories(r.getCategories());
    }

    /**
     * カテゴリーのチェックボックスで、指定したカテゴリーだけをチェック状態にする。
     * @param cats チェックするカテゴリー
     */
    private void selectCategories(EnumSet<RecipeCategory> cats) {
        RecipeCategory[] all = RecipeCategory.values();
        for (int i = 0; i < all.length; i++) {
            categoryChecks[i].setChecked(cats.contains(all[i]));
        }
    }

    /**
     * AI自動提案を非同期で要求し、成功時はフォームに反映する。
     * @param btnAi 押下されたAIボタン(処理中は無効化される)
     */
    private void requestAiSuggestion(Button btnAi) {
        if (!UIComponents.ensureAiReady(owner, owner.getAiSettings())) return;
        btnAi.setText("生成中...");
        btnAi.setEnabled(false);

        String url = urlField.getText().toString().trim();
        List<String> names = owner.getIngredientMaster().getAllIngredientNames();
        UIComponents.runAsync(
                () -> owner.getAiSettings().createService().suggestRecipe(url, names, RecipeCategory.displayNames()),
                suggestion -> {
                    btnAi.setText(AI_BUTTON_LABEL);
                    btnAi.setEnabled(true);
                    if (applyAiResult(suggestion, url)) {
                        UIComponents.info(owner, "AIがレシピを提案しました");
                    }
                    // 検出できなかった場合はフォームを書き換えず、ダイアログも出さない
                },
                ex -> {
                    btnAi.setText(AI_BUTTON_LABEL);
                    btnAi.setEnabled(true);
                    UIComponents.info(owner, "エラー",
                            "AIの呼び出しに失敗しました。\n設定内容を確認してください。\n\n" + ex.getMessage());
                    ex.printStackTrace();
                });
    }

    /**
     * AIの返したレシピ(タイトル・食材・分量・カテゴリー)をフォームに反映する。
     * タイトルが空、または食材が0個の場合は「検出できなかった」と判断し、
     * フォームには一切手を加えず false を返す。
     * AIがカテゴリーを1つも選べなかった場合は、カテゴリーの選択を変更しない。
     * @param suggestion AIが提案したレシピ
     * @param url        AIに渡したURL
     * @return 反映に成功すれば true、検出失敗時は false
     */
    private boolean applyAiResult(RecipeSuggestion suggestion, String url) {
        // AIが未知の食材を返した場合は OTHER カテゴリで自動的にマスタに追加される
        Recipe suggested = BatchUrlImporter.toRecipe(suggestion, url, owner.getIngredientMaster());
        if (suggested == null) return false;

        titleField.setText(suggested.getTitle());
        showIngredients(suggested);
        EnumSet<RecipeCategory> aiCategories = RecipeCategory.fromLabels(suggestion.categories);
        if (!aiCategories.isEmpty()) selectCategories(aiCategories);
        return true;
    }

    /**
     * 入力内容を検証し、新規登録または更新を行う。
     * 終了後はウェルカム画面に戻る。
     */
    private void submitRecipe() {
        String title = titleField.getText().toString().trim();
        String url = urlField.getText().toString().trim();

        if (title.isEmpty() || url.isEmpty() || rows.isEmpty()) {
            UIComponents.info(owner, "入力エラー", "入力されていない箇所があります");
            return;
        }

        ArrayList<Ingredient> ings = new ArrayList<>();
        Map<String, Quantity> quantities = new LinkedHashMap<>();
        for (IngredientRow row : rows) {
            ings.add(row.ingredient);
            quantities.put(row.ingredient.getName(), Quantity.of(
                    row.amountField.getText().toString(), row.unitField.getText().toString()));
        }

        // 未選択の場合は Recipe 側で OTHER になる
        EnumSet<RecipeCategory> categories = EnumSet.noneOf(RecipeCategory.class);
        RecipeCategory[] all = RecipeCategory.values();
        for (int i = 0; i < all.length; i++) {
            if (categoryChecks[i].isChecked()) categories.add(all[i]);
        }

        Recipe recipe = new Recipe(title, url, ings, quantities, categories);
        if (editTarget == null) {
            owner.getAllRecipeList().addRecipe(recipe);
            UIComponents.info(owner, "レシピ「" + title + "」を登録しました");
        } else {
            recipe.setId(editTarget.getId());
            owner.getAllRecipeList().updateRecipe(recipe);
            UIComponents.info(owner, "レシピ「" + title + "」を更新しました");
        }
        owner.showWelcome();
    }
}
