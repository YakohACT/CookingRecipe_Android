package main.java.Recipe;

import java.util.ArrayList;

/**
 * 全レシピをメモリに保持し、SQLite と同期させるクラス。
 * add/delete は即時SQLに反映するため write() の呼び出しは不要(no-op として残置)。
 */
public class AllRecipeList {

    private final RecipeRepository repository;
    private ArrayList<Recipe> recipeList;

    /**
     * リポジトリを初期化し、起動時に SQLite から全レシピを読み込む。
     * 読み込みに失敗してもアプリは起動できるよう、空のリストにフォールバックする。
     * @param ingredientMaster 食材名から Ingredient オブジェクトを解決するためのマスタ
     */
    public AllRecipeList(IngredientMaster ingredientMaster) {
        this.repository = new RecipeRepository(RecipeRepository.resolveDbPath(), ingredientMaster);
        try {
            this.recipeList = new ArrayList<>(repository.findAll());
        } catch (Exception e) {
            System.err.println("[エラー] レシピの読み込みに失敗しました: " + e.getMessage());
            e.printStackTrace();
            this.recipeList = new ArrayList<>();
        }
    }

    /**
     * メモリ上のレシピリストを返す。
     * @return レシピリスト(内部参照そのまま)
     */
    public ArrayList<Recipe> getRecipeList() {
        return recipeList;
    }

    /**
     * 新規レシピを SQLite に挿入し、メモリ上のリストにも追加する。
     * 採番された ID は recipe.setId() で書き戻される。
     * @param recipe 追加するレシピ(id は 0 のもの)
     */
    public void addRecipe(Recipe recipe) {
        long id = repository.insert(recipe);
        recipe.setId(id);
        recipeList.add(recipe);
    }

    /**
     * 指定レシピを SQLite とメモリ上のリストの両方から削除する。
     * @param recipe 削除するレシピ
     */
    public void deleteRecipe(Recipe recipe) {
        if (recipe.getId() != 0) {
            repository.delete(recipe.getId());
        }
        recipeList.remove(recipe);
    }

    /**
     * 既存レシピを更新する。SQLiteと同期し、メモリ上のリストの該当エントリも差し替える。
     * @param recipe 同じ id を持つ更新後のレシピ
     */
    public void updateRecipe(Recipe recipe) {
        repository.update(recipe);
        for (int i = 0; i < recipeList.size(); i++) {
            if (recipeList.get(i).getId() == recipe.getId()) {
                recipeList.set(i, recipe);
                return;
            }
        }
    }

}
