package main.java.UI;

import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import main.java.MainActivity;
import main.java.Recipe.Ingredient;
import main.java.Recipe.IngredientCategory;
import main.java.Recipe.Recipe;
import main.java.Recipe.RecipeCategory;

import java.util.ArrayList;
import java.util.List;

/**
 * レシピ閲覧画面パネル（タイトル検索/食材検索/カテゴリ検索の3タブ構成）。
 * 各タブで選択中のレシピを編集パネルへ送る「編集」ボタンと、削除する「削除」ボタンを持つ
 * (デスクトップ版 Swing パネルの Android View 置き換え。一覧と詳細は縦に並べる)
 */
public class ViewRecipePanel extends LinearLayout {

    private static final String[] TAB_NAMES = {"タイトルから検索", "食材から検索", "カテゴリーから検索"};

    private final MainActivity owner;
    private final FrameLayout tabContent;
    private final Button[] tabButtons = new Button[TAB_NAMES.length];
    private final View[] tabs = new View[TAB_NAMES.length];
    /** 各タブのレシピ一覧。削除したレシピを全タブから取り除くために保持する */
    private final List<RecipeListWithDetail> recipeLists = new ArrayList<>();

    /**
     * 閲覧パネルを構築する。
     * @param owner 共有状態(レシピリスト等)にアクセスするためのアクティビティ参照
     */
    public ViewRecipePanel(MainActivity owner) {
        super(owner);
        this.owner = owner;
        setOrientation(VERTICAL);
        int pad = UIComponents.dp(owner, 8);
        setPadding(pad, pad, pad, pad);

        // ===== タブ切替ボタン列(JTabbedPane の置き換え) =====
        LinearLayout tabBar = new LinearLayout(owner);
        tabBar.setOrientation(HORIZONTAL);
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int index = i;
            Button b = UIComponents.createButton(owner, TAB_NAMES[i]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            b.setOnClickListener(v -> selectTab(index));
            tabButtons[i] = b;
            tabBar.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        addView(tabBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tabContent = new FrameLayout(owner);
        addView(tabContent, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        tabs[0] = buildTitleTab();
        tabs[1] = buildIngredientTab();
        tabs[2] = buildCategoryTab();
        selectTab(0);
    }

    /**
     * 指定タブを表示し、タブボタンの選択色を更新する。
     * @param index 表示するタブ番号
     */
    private void selectTab(int index) {
        tabContent.removeAllViews();
        tabContent.addView(tabs[index], new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        for (int i = 0; i < tabButtons.length; i++) {
            tabButtons[i].setBackgroundTintList(ColorStateList.valueOf(
                    i == index ? Theme.COLOR_PRIMARY : Theme.COLOR_MUTED));
            tabButtons[i].setTextColor(Theme.COLOR_TEXT_LIGHT);
        }
    }

    /** レシピ一覧 + 詳細 + 「削除」「編集」ボタンの共通部品 */
    private final class RecipeListWithDetail {
        final LinearLayout root;
        final List<Recipe> recipes;
        final ArrayAdapter<Recipe> adapter;
        final ListView listView;
        final TextView detailArea;
        final Button btnDelete;
        final Button btnEdit;
        Recipe selected;

        /**
         * @param recipes 一覧に表示するレシピリスト(参照を保持し、内容を変えたら {@link #refresh()} を呼ぶ)
         */
        RecipeListWithDetail(List<Recipe> recipes) {
            this.recipes = recipes;
            root = new LinearLayout(owner);
            root.setOrientation(VERTICAL);

            adapter = UIComponents.createRecipeAdapter(owner, recipes);
            listView = new ListView(owner);
            listView.setAdapter(adapter);
            listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
            root.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            detailArea = UIComponents.createDetailArea(owner);
            ScrollView detailScroll = new ScrollView(owner);
            detailScroll.addView(detailArea);
            root.addView(detailScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            LinearLayout buttons = new LinearLayout(owner);
            buttons.setOrientation(HORIZONTAL);
            btnDelete = UIComponents.createColoredButton(owner, "選択中のレシピを削除", Theme.COLOR_DELETE);
            btnDelete.setOnClickListener(v -> deleteSelected(this));
            buttons.addView(btnDelete, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            btnEdit = UIComponents.createPrimaryButton(owner, "選択中のレシピを編集");
            btnEdit.setOnClickListener(v -> {
                if (selected != null) owner.showPanel(new RegisterRecipePanel(owner, selected));
            });
            buttons.addView(btnEdit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            root.addView(buttons, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            listView.setOnItemClickListener((parent, view, position, id) -> select(adapter.getItem(position)));
            select(null);
            recipeLists.add(this);
        }

        /**
         * 選択中のレシピを切り替え、詳細表示とボタンの有効/無効を更新する。
         * 選択が無いときは前のレシピが詳細に残らないよう消す
         * @param r 選択したレシピ(null で選択解除)
         */
        void select(Recipe r) {
            selected = r;
            if (r != null) UIComponents.updateDetailArea(detailArea, r);
            else detailArea.setText("");
            btnDelete.setEnabled(r != null);
            btnEdit.setEnabled(r != null);
        }

        /**
         * 一覧の内容が変わったあとに表示を更新し、選択を解除する。
         */
        void refresh() {
            adapter.notifyDataSetChanged();
            listView.clearChoices();
            select(null);
        }
    }

    /**
     * 選択中のレシピを確認のうえ削除し、全タブの一覧から取り除く。
     * @param source 削除対象を選択している一覧
     */
    private void deleteSelected(RecipeListWithDetail source) {
        Recipe target = source.selected;
        if (target == null) return;
        UIComponents.confirm(owner, "削除の確認", "本当に「" + target.getTitle() + "」を削除しますか？", () -> {
            owner.getAllRecipeList().deleteRecipe(target);
            for (RecipeListWithDetail list : recipeLists) {
                if (list.recipes.remove(target) || list.selected == target) list.refresh();
            }
        });
    }

    /**
     * タイトル一覧から選択する1つ目のタブを構築する。
     * @return タブコンテンツのビュー
     */
    private View buildTitleTab() {
        RecipeListWithDetail widget = new RecipeListWithDetail(
                new ArrayList<>(owner.getAllRecipeList().getRecipeList()));
        return widget.root;
    }

    /**
     * 食材を AND 条件で絞り込む2つ目のタブを構築する。
     * @return タブコンテンツのビュー
     */
    private View buildIngredientTab() {
        LinearLayout tab = new LinearLayout(owner);
        tab.setOrientation(VERTICAL);

        // --- 1. カテゴリと食材を選んで条件に追加 ---
        tab.addView(UIComponents.createLabel(owner, "1. カテゴリと食材を選んで条件に追加:"));
        LinearLayout pickRow = new LinearLayout(owner);
        pickRow.setOrientation(HORIZONTAL);
        Spinner catSpinner = new Spinner(owner);
        catSpinner.setAdapter(UIComponents.createSpinnerAdapter(owner, IngredientCategory.values()));
        pickRow.addView(catSpinner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Spinner ingSpinner = new Spinner(owner);
        pickRow.addView(ingSpinner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tab.addView(pickRow);
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

        // --- 2. 現在の検索条件 ---
        List<Ingredient> conditions = new ArrayList<>();
        TextView condLabel = UIComponents.createLabel(owner, "");
        Runnable refreshCondLabel = () -> {
            StringBuilder sb = new StringBuilder("2. 現在の検索条件: ");
            if (conditions.isEmpty()) sb.append("(なし)");
            for (int i = 0; i < conditions.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(conditions.get(i).getName());
            }
            condLabel.setText(sb.toString());
        };
        refreshCondLabel.run();

        LinearLayout btnRow = new LinearLayout(owner);
        btnRow.setOrientation(HORIZONTAL);
        Button btnAddCond = UIComponents.createButton(owner, "検索条件に追加");
        btnAddCond.setOnClickListener(v -> {
            Object selected = ingSpinner.getSelectedItem();
            if (selected instanceof Ingredient && !conditions.contains(selected)) {
                conditions.add((Ingredient) selected);
                refreshCondLabel.run();
            }
        });
        btnRow.addView(btnAddCond, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button btnClearCond = UIComponents.createButton(owner, "条件をクリア");
        btnClearCond.setOnClickListener(v -> {
            conditions.clear();
            refreshCondLabel.run();
        });
        btnRow.addView(btnClearCond, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tab.addView(btnRow);
        tab.addView(condLabel);

        // --- 検索結果 ---
        List<Recipe> results = new ArrayList<>();
        RecipeListWithDetail widget = new RecipeListWithDetail(results);

        Button btnSearch = UIComponents.createPrimaryButton(owner, "この食材をすべて含むレシピを検索");
        btnSearch.setOnClickListener(v -> {
            results.clear();
            for (Recipe r : owner.getAllRecipeList().getRecipeList()) {
                if (r.getIngredients().containsAll(conditions)) results.add(r);
            }
            widget.refresh();
        });
        tab.addView(btnSearch);

        tab.addView(widget.root, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return tab;
    }

    /**
     * カテゴリー選択でレシピを絞り込む3つ目のタブを構築する。
     * @return タブコンテンツのビュー
     */
    private View buildCategoryTab() {
        LinearLayout tab = new LinearLayout(owner);
        tab.setOrientation(VERTICAL);

        LinearLayout topRow = new LinearLayout(owner);
        topRow.setOrientation(HORIZONTAL);
        topRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        topRow.addView(UIComponents.createLabel(owner, "カテゴリー: "));
        Spinner categorySpinner = new Spinner(owner);
        categorySpinner.setAdapter(UIComponents.createSpinnerAdapter(owner, RecipeCategory.values()));
        topRow.addView(categorySpinner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tab.addView(topRow);

        List<Recipe> filtered = new ArrayList<>();
        RecipeListWithDetail widget = new RecipeListWithDetail(filtered);
        tab.addView(widget.root, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        categorySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                RecipeCategory selected = (RecipeCategory) parent.getItemAtPosition(position);
                filtered.clear();
                for (Recipe r : owner.getAllRecipeList().getRecipeList()) {
                    if (r.getCategories().contains(selected)) filtered.add(r);
                }
                widget.refresh();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        return tab;
    }
}
