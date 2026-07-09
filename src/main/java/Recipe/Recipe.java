package main.java.Recipe;

import java.util.ArrayList;
import java.util.EnumSet;

/**
 * レシピ情報を管理するクラス。
 * 1つのレシピは複数のカテゴリーを持つことができる。
 * Serializable は廃止
 * id はDB主キー(0は未永続化を表す)。
 */
public class Recipe {

    private long id;
    private String title;
    private String url;
    private ArrayList<Ingredient> ingredients;
    private EnumSet<RecipeCategory> categories;

    /**
     * 新規レシピを生成する。
     * @param title       レシピ名(必須)
     * @param url         参照URL(必須)
     * @param ingredients 使用食材のリスト
     * @param categories  レシピカテゴリーの集合(空またはnullなら OTHER のみ)
     */
    public Recipe(String title, String url, ArrayList<Ingredient> ingredients, EnumSet<RecipeCategory> categories) {
        this.title = title;
        this.url = url;
        this.ingredients = ingredients;
        this.categories = (categories == null || categories.isEmpty())
                ? EnumSet.of(RecipeCategory.OTHER)
                : EnumSet.copyOf(categories);
    }

    /**
     * DB主キーを返す。
     * @return DB上のID。未永続化レシピでは 0
     */
    public long getId() { return id; }

    /**
     * 永続化後にDBが採番したIDを設定する。
     * @param id 採番されたID
     */
    public void setId(long id) { this.id = id; }

    /**
     * @return レシピ名
     */
    public String getTitle() { return title; }

    /**
     * @return 参照URL
     */
    public String getUrl() { return url; }

    /**
     * @return 使用食材のリスト(内部参照そのまま)
     */
    public ArrayList<Ingredient> getIngredients() { return ingredients; }

    /**
     * categories が null/空の場合は {@link RecipeCategory#OTHER} のみのSetを返す。
     * 返り値は防御的コピーなので、変更しても本オブジェクトには影響しない。
     * @return レシピカテゴリー集合のコピー
     */
    public EnumSet<RecipeCategory> getCategories() {
        if (categories == null || categories.isEmpty()) return EnumSet.of(RecipeCategory.OTHER);
        return EnumSet.copyOf(categories);
    }
}
