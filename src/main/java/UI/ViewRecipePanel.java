package main.java.UI;

import android.content.res.ColorStateList;
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

/**
 * レシピ閲覧画面パネル（タイトル検索/食材検索/カテゴリ検索の3タブ構成）。
 * 各タブで選択中のレシピを編集パネルへ送る「編集」ボタンを持つ。
 * (デスクトップ版 Swing パネルの Android View 置き換え)
 */
public class ViewRecipePanel extends LinearLayout {

    private final MainActivity owner;
    private final FrameLayout tabContent;
    private final Button[] tabButtons = new Button[3];

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

        // ===== タブ切替ボタン列 =====
        LinearLayout tabBar = new LinearLayout(owner);
        tabBar.setOrientation(HORIZONTAL);
        String[] tabNames = {"タイトルから検索", "食材から検索", "カテゴリーから検索"};
        View[] tabs = new View[3];
        for (int i = 0; i < 3; i++) {
            final int index = i;
            Button b = new Button(owner);
            b.setText(tabNames[i]);
            b.setAllCaps(false);
            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
            tabButtons[i] = b;
            b.setOnClickListener(v -> selectTab(index, tabs));
            tabBar.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        addView(tabBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tabContent = new FrameLayout(owner);
        addView(tabContent, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        tabs[0] = buildTitleTab();
        tabs[1] = buildIngredientTab();
        tabs[2] = buildCategoryTab();
        selectTab(0, tabs);
    }

    /**
     * 指定タブを表示し、タブボタンの選択色を更新する。
     * @param index 表示するタブ番号
     * @param tabs  各タブのコンテンツビュー
     */
    private void selectTab(int index, View[] tabs) {
        tabContent.removeAllViews();
        if (tabs[index] != null) {
            tabContent.addView(tabs[index], new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        for (int i = 0; i < tabButtons.length; i++) {
            tabButtons[i].setBackgroundTintList(ColorStateList.valueOf(
                    i == index ? Theme.COLOR_PRIMARY : 0xFF9E9E9E));
            tabButtons[i].setTextColor(Theme.COLOR_TEXT_LIGHT);
        }
    }

    /** レシピ一覧 + 詳細 + 編集ボタンの共通部品 */
    private final class RecipeListWithDetail {
        final LinearLayout root;
        final ListView listView;
        final ArrayAdapter<Recipe> adapter;
        final TextView detailArea;
        Recipe selected;

        /**
         * @param recipes 一覧に表示するレシピリスト(参照を保持)
         */
        RecipeListWithDetail(ArrayList<Recipe> recipes) {
            root = new LinearLayout(owner);
            root.setOrientation(VERTICAL);

            adapter = UIComponents.createRecipeAdapter(owner, recipes);
            listView = new ListView(owner);
            listView.setAdapter(adapter);
            listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
            root.addView(listView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            detailArea = UIComponents.createDetailArea(owner);
            ScrollView detailScroll = new ScrollView(owner);
            detailScroll.addView(detailArea);
            root.addView(detailScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            Button btnEdit = UIComponents.createPrimaryButton(owner, "選択中のレシピを編集");
            btnEdit.setEnabled(false);
            btnEdit.setOnClickListener(v -> {
                if (selected != null) owner.showPanel(new RegisterRecipePanel(owner, selected));
            });
            root.addView(btnEdit, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            listView.setOnItemClickListener((parent, view, position, id) -> {
                selected = adapter.getItem(position);
                if (selected != null) UIComponents.updateDetailArea(detailArea, selected);
                btnEdit.setEnabled(selected != null);
            });
        }
    }

    /**
     * タイトル一覧から選択する1つ目のタブを構築する。
     * @return タブコンテンツのビュー
     */
    private View buildTitleTab() {
        ArrayList<Recipe> recipes = new ArrayList<>(owner.getAllRecipeList().getRecipeList());
        RecipeListWithDetail widget = new RecipeListWithDetail(recipes);
        return widget.root;
    }

    /**
     * 食材を AND 条件で絞り込む2つ目のタブを構築する。
     * @return タブコンテンツのビュー
     */
    private View buildIngredientTab() {
        LinearLayout tab = new LinearLayout(owner);
        tab.setOrientation(VERTICAL);

        // --- 検索条件ビルダー ---
        tab.addView(UIComponents.createLabel(owner, "1. カテゴリと食材を選んで条件に追加:"));

        Spinner catSpinner = new Spinner(owner);
        catSpinner.setAdapter(UIComponents.createSpinnerAdapter(owner, IngredientCategory.values()));
        tab.addView(catSpinner);

        Spinner ingSpinner = new Spinner(owner);
        tab.addView(ingSpinner);

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

        ArrayList<Ingredient> conditions = new ArrayList<>();
        TextView condLabel = UIComponents.createLabel(owner, "2. 現在の検索条件: (なし)");

        LinearLayout btnRow = new LinearLayout(owner);
        btnRow.setOrientation(HORIZONTAL);
        Button btnAddCond = new Button(owner);
        btnAddCond.setText("検索条件に追加");
        btnAddCond.setAllCaps(false);
        Button btnClearCond = new Button(owner);
        btnClearCond.setText("条件をクリア");
        btnClearCond.setAllCaps(false);
        btnRow.addView(btnAddCond, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnRow.addView(btnClearCond, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tab.addView(btnRow);
        tab.addView(condLabel);

        Runnable refreshCondLabel = () -> {
            if (conditions.isEmpty()) {
                condLabel.setText("2. 現在の検索条件: (なし)");
            } else {
                StringBuilder sb = new StringBuilder("2. 現在の検索条件: ");
                for (int i = 0; i < conditions.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(conditions.get(i).getName());
                }
                condLabel.setText(sb.toString());
            }
        };

        btnAddCond.setOnClickListener(v -> {
            Object sel = ingSpinner.getSelectedItem();
            if (sel instanceof Ingredient && !conditions.contains(sel)) {
                conditions.add((Ingredient) sel);
                refreshCondLabel.run();
            }
        });
        btnClearCond.setOnClickListener(v -> {
            conditions.clear();
            refreshCondLabel.run();
        });

        // --- 検索結果 ---
        ArrayList<Recipe> results = new ArrayList<>();
        RecipeListWithDetail widget = new RecipeListWithDetail(results);

        Button btnSearch = UIComponents.createPrimaryButton(owner, "この食材をすべて含むレシピを検索");
        btnSearch.setOnClickListener(v -> {
            results.clear();
            for (Recipe r : owner.getAllRecipeList().getRecipeList()) {
                if (r.getIngredients().containsAll(conditions)) results.add(r);
            }
            widget.adapter.notifyDataSetChanged();
            widget.listView.clearChoices();
            widget.selected = null;
            widget.detailArea.setText("");
        });
        tab.addView(btnSearch);

        tab.addView(widget.root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
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
        TextView catLabel = UIComponents.createLabel(owner, "カテゴリー: ");
        topRow.addView(catLabel);
        Spinner categorySpinner = new Spinner(owner);
        categorySpinner.setAdapter(UIComponents.createSpinnerAdapter(owner, RecipeCategory.values()));
        topRow.addView(categorySpinner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tab.addView(topRow);

        ArrayList<Recipe> filtered = new ArrayList<>();
        RecipeListWithDetail widget = new RecipeListWithDetail(filtered);
        tab.addView(widget.root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        categorySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                RecipeCategory selected = (RecipeCategory) parent.getItemAtPosition(position);
                filtered.clear();
                for (Recipe r : owner.getAllRecipeList().getRecipeList()) {
                    if (r.getCategories().contains(selected)) filtered.add(r);
                }
                widget.adapter.notifyDataSetChanged();
                widget.listView.clearChoices();
                widget.selected = null;
                widget.detailArea.setText("");
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        return tab;
    }
}
