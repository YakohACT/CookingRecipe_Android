package main.java.UI;

import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import main.java.MainActivity;
import main.java.AI.RecipeAIService;
import main.java.Recipe.Ingredient;
import main.java.Recipe.IngredientCategory;
import main.java.Recipe.Recipe;
import main.java.Recipe.RecipeCategory;

import java.util.ArrayList;
import java.util.EnumSet;

/**
 * レシピ登録/編集画面パネル（AI自動提案機能付き）。
 * editTarget が null なら新規登録、非nullなら既存レシピの更新として動作する。
 * (デスクトップ版 Swing パネルの Android View 置き換え)
 */
public class RegisterRecipePanel extends ScrollView {

    private final MainActivity owner;
    private final Recipe editTarget;
    private final EditText titleField;
    private final EditText urlField;
    /** RecipeCategory.values() と同じ並びのチェックボックス */
    private final CheckBox[] categoryChecks = new CheckBox[RecipeCategory.values().length];
    /** 現在選択されている食材(重複なし・順序保持) */
    private final ArrayList<Ingredient> selectedIngredients = new ArrayList<>();
    private final LinearLayout selectedContainer;

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

        // ===== ヘッダー(タイトル + AIボタン) =====
        LinearLayout header = new LinearLayout(owner);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView titleLabel = UIComponents.createTitleLabel(owner,
                editTarget == null ? "新規レシピ登録" : "レシピ編集");
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.gravity = android.view.Gravity.CENTER_VERTICAL;
        header.addView(titleLabel, titleLp);
        Button btnAi = UIComponents.createColoredButton(owner, "✨ AI自動提案", Theme.COLOR_AI);
        header.addView(btnAi);
        form.addView(header);

        // ===== タイトル / URL 入力 =====
        form.addView(UIComponents.createLabel(owner, "タイトル:"));
        titleField = UIComponents.createStyledTextField(owner);
        form.addView(titleField);
        form.addView(UIComponents.createLabel(owner, "URL:"));
        urlField = UIComponents.createStyledTextField(owner);
        urlField.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(urlField);

        addSpacer(form, 10);

        // ===== レシピカテゴリー(複数選択チェックボックス) =====
        form.addView(UIComponents.createLabel(owner, "レシピのカテゴリー (複数選択可):"));
        GridLayout catGrid = new GridLayout(owner);
        catGrid.setColumnCount(3);
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
            catGrid.addView(cb);
        }
        form.addView(catGrid);

        addSpacer(form, 10);

        // ===== 食材選択(カテゴリSpinner → 食材Spinner → 追加ボタン) =====
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
                ArrayList<Ingredient> list = owner.getIngredientMaster().searchIngredient(cat);
                ingSpinner.setAdapter(UIComponents.createSpinnerAdapter(owner, list));
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        Button btnAdd = new Button(owner);
        btnAdd.setText("選択した食材を追加");
        btnAdd.setAllCaps(false);
        btnAdd.setOnClickListener(v -> {
            Object sel = ingSpinner.getSelectedItem();
            if (sel instanceof Ingredient && !selectedIngredients.contains(sel)) {
                selectedIngredients.add((Ingredient) sel);
                refreshSelectedList();
            }
        });
        form.addView(btnAdd);

        addSpacer(form, 16);

        // ===== 選択済み食材一覧(タップで解除) =====
        form.addView(UIComponents.createLabel(owner, "使用する食材 (タップで解除):"));
        selectedContainer = new LinearLayout(owner);
        selectedContainer.setOrientation(LinearLayout.VERTICAL);
        form.addView(selectedContainer);

        addSpacer(form, 16);

        // ===== 保存ボタン =====
        Button btnSubmit = UIComponents.createPrimaryButton(owner,
                editTarget == null ? "レシピを保存" : "レシピを更新");
        btnSubmit.setOnClickListener(v -> submitRecipe());
        form.addView(btnSubmit);

        btnAi.setOnClickListener(v -> requestAiSuggestion(btnAi));

        // 編集モードのときは既存レシピの内容をフォームに復元
        if (editTarget != null) {
            prefillFromRecipe(editTarget);
        }
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
     * 選択済み食材の表示を selectedIngredients の内容で再構築する。
     * 各行はタップすると選択解除される。
     */
    private void refreshSelectedList() {
        selectedContainer.removeAllViews();
        if (selectedIngredients.isEmpty()) {
            TextView empty = UIComponents.createLabel(owner, "(まだ食材が選択されていません)");
            empty.setTextColor(0xFF888888);
            selectedContainer.addView(empty);
            return;
        }
        for (Ingredient ing : new ArrayList<>(selectedIngredients)) {
            TextView row = UIComponents.createLabel(owner, "・" + ing.getName() + "　✕");
            int p = UIComponents.dp(owner, 8);
            row.setPadding(p, p, p, p);
            row.setOnClickListener(v -> {
                selectedIngredients.remove(ing);
                refreshSelectedList();
            });
            selectedContainer.addView(row);
        }
    }

    /**
     * 編集対象レシピの内容をフォーム要素に流し込む。
     * @param r 編集対象のレシピ
     */
    private void prefillFromRecipe(Recipe r) {
        titleField.setText(r.getTitle());
        urlField.setText(r.getUrl());
        for (Ingredient ing : r.getIngredients()) {
            if (!selectedIngredients.contains(ing)) selectedIngredients.add(ing);
        }
        refreshSelectedList();
        RecipeCategory[] all = RecipeCategory.values();
        EnumSet<RecipeCategory> cats = r.getCategories();
        for (int i = 0; i < all.length; i++) {
            categoryChecks[i].setChecked(cats.contains(all[i]));
        }
    }

    /**
     * AI自動提案を非同期で要求し、成功時はフォームに反映する。
     * @param btnAi 押下されたAIボタン(処理中は無効化される)
     */
    private void requestAiSuggestion(Button btnAi) {
        // Ollama はローカルLLMなのでAPIキー不要。それ以外はAPIキー必須
        if (owner.getCurrentAiProvider() != RecipeAIService.Provider.OLLAMA
                && owner.getCurrentApiKey().isEmpty()) {
            UIComponents.info(owner, "先に「AI設定」メニューからAPIキーを入力してください");
            return;
        }
        btnAi.setText("生成中...");
        btnAi.setEnabled(false);

        String url = urlField.getText().toString().trim();
        UIComponents.runAsync(
                () -> {
                    RecipeAIService aiService = new RecipeAIService();
                    aiService.setConfig(owner.getCurrentAiProvider(), owner.getCurrentApiKey(), owner.getCurrentModelName());
                    return aiService.suggestRecipe(url, owner.getIngredientMaster().getAllIngredients());
                },
                result -> {
                    if (applyAiResult(result)) {
                        UIComponents.info(owner, "AIがレシピを提案しました");
                    }
                    // 検出できなかった場合はフォームを書き換えず、ダイアログも出さない
                    btnAi.setText("✨ AI自動提案");
                    btnAi.setEnabled(true);
                },
                ex -> {
                    UIComponents.info(owner, "エラー",
                            "AIの呼び出しに失敗しました。\n設定内容を確認してください。\n\n" + ex.getMessage());
                    ex.printStackTrace();
                    btnAi.setText("✨ AI自動提案");
                    btnAi.setEnabled(true);
                });
    }

    /**
     * AIの返したレシピをフォームに反映する。
     * タイトルが空、または食材マスタとマッチする食材が0個の場合は
     * 「検出できなかった」と判断し、フォームには一切手を加えず false を返す。
     * @param result AIから返ってきた {タイトル, "食材1,食材2,…"} 配列
     * @return 反映に成功すれば true、検出失敗時は false
     */
    private boolean applyAiResult(String[] result) {
        String aiTitle = (result == null || result.length < 1 || result[0] == null) ? "" : result[0].trim();
        String aiIngsRaw = (result == null || result.length < 2 || result[1] == null) ? "" : result[1];

        ArrayList<Ingredient> matched = new ArrayList<>();
        for (String aiIngName : aiIngsRaw.split(",")) {
            String cleanName = aiIngName.trim();
            if (cleanName.isEmpty()) continue;
            Ingredient resolved = null;
            for (Ingredient ing : owner.getIngredientMaster().getAllIngredients()) {
                if (ing.getName().equals(cleanName)) {
                    resolved = ing;
                    break;
                }
            }
            // AIが未知の食材を返した場合は OTHER カテゴリで自動的にマスタに追加
            if (resolved == null) {
                resolved = owner.getIngredientMaster().addIngredient(cleanName);
            }
            if (resolved != null && !matched.contains(resolved)) {
                matched.add(resolved);
            }
        }

        if (aiTitle.isEmpty() || matched.isEmpty()) {
            return false;
        }

        titleField.setText(aiTitle);
        selectedIngredients.clear();
        selectedIngredients.addAll(matched);
        refreshSelectedList();
        return true;
    }

    /**
     * 入力内容を検証し、新規登録または更新を行う。
     * 終了後はウェルカム画面に戻る。
     */
    private void submitRecipe() {
        String title = titleField.getText().toString().trim();
        String url = urlField.getText().toString().trim();

        if (title.isEmpty() || url.isEmpty() || selectedIngredients.isEmpty()) {
            UIComponents.info(owner, "入力エラー", "入力されていない箇所があります");
            return;
        }

        ArrayList<Ingredient> ings = new ArrayList<>(selectedIngredients);

        EnumSet<RecipeCategory> categories = EnumSet.noneOf(RecipeCategory.class);
        RecipeCategory[] all = RecipeCategory.values();
        for (int i = 0; i < all.length; i++) {
            if (categoryChecks[i].isChecked()) categories.add(all[i]);
        }
        if (categories.isEmpty()) categories.add(RecipeCategory.OTHER);

        if (editTarget == null) {
            owner.getAllRecipeList().addRecipe(new Recipe(title, url, ings, categories));
            UIComponents.info(owner, "レシピ「" + title + "」を登録しました");
        } else {
            Recipe updated = new Recipe(title, url, ings, categories);
            updated.setId(editTarget.getId());
            owner.getAllRecipeList().updateRecipe(updated);
            UIComponents.info(owner, "レシピ「" + title + "」を更新しました");
        }
        owner.showWelcome();
    }
}
