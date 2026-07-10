package main.java.UI;

import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;

import main.java.MainActivity;
import main.java.Recipe.Recipe;

import java.util.ArrayList;

/**
 * レシピ削除画面パネル。
 * (デスクトップ版 Swing パネルの Android View 置き換え)
 */
public class DeleteRecipePanel extends LinearLayout {

    private Recipe selected;

    /**
     * 削除パネルを構築する。
     * @param owner 共有状態(レシピリスト等)へアクセスするためのアクティビティ参照
     */
    public DeleteRecipePanel(MainActivity owner) {
        super(owner);
        setOrientation(VERTICAL);
        int pad = UIComponents.dp(owner, 16);
        setPadding(pad, pad, pad, pad);

        addView(UIComponents.createLabel(owner, "削除するレシピを選択してください"));

        ArrayList<Recipe> recipes = new ArrayList<>(owner.getAllRecipeList().getRecipeList());
        ArrayAdapter<Recipe> adapter = UIComponents.createRecipeAdapter(owner, recipes);
        ListView list = new ListView(owner);
        list.setAdapter(adapter);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setOnItemClickListener((parent, view, position, id) -> selected = adapter.getItem(position));
        addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button btnDel = UIComponents.createColoredButton(owner, "選択したレシピを削除", Theme.COLOR_DELETE);
        btnDel.setOnClickListener(v -> {
            if (selected == null) return;
            Recipe target = selected;
            UIComponents.confirm(owner, "削除の確認",
                    "本当に「" + target.getTitle() + "」を削除しますか？",
                    () -> {
                        owner.getAllRecipeList().deleteRecipe(target);
                        recipes.remove(target);
                        adapter.notifyDataSetChanged();
                        list.clearChoices();
                        selected = null;
                    });
        });
        addView(btnDel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }
}
