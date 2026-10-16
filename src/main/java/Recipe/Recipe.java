package main.java.Recipe;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * レシピ情報を管理するクラス
 * 1つのレシピは複数のカテゴリーを持つことができる
 */
public class Recipe {
    /** DB上に割り当てた番号 */
    private long id;
    /** タイトル名 */
    private String title;
    /** 引用元URL */
    private String url;
    /** 使う食材 */
    private ArrayList<Ingredient> ingredients;
    /** 食材名 → 分量(分量を指定した食材だけを持つ) */
    private Map<String, Quantity> quantities;
    /** 登録カテゴリー */
    private EnumSet<RecipeCategory> categories;

    /**
     * コンストラクタ
     * @param title       レシピ名
     * @param url         参照URL
     * @param ingredients 使用食材のリスト
     * @param quantities  食材名 → 分量(nullなら分量なし。使用食材に無い名前は無視する)
     * @param categories  レシピカテゴリーの集合(空またはnullなら OTHER のみ)
     */
    public Recipe(String title, String url, ArrayList<Ingredient> ingredients,
                  Map<String, Quantity> quantities, EnumSet<RecipeCategory> categories) {
        this.title = title;
        this.url = url;
        this.ingredients = ingredients;
        this.quantities = new LinkedHashMap<>();
        if (quantities != null) {
            for (Ingredient ing : ingredients) {
                Quantity q = quantities.get(ing.getName());
                if (q != null && !q.isEmpty()) this.quantities.put(ing.getName(), q);
            }
        }
        this.categories = (categories == null || categories.isEmpty())
                ? EnumSet.of(RecipeCategory.OTHER)
                : EnumSet.copyOf(categories);
    }

    /**
     * idのゲッター
     * @return DB上のID、未永続化レシピは0
     */
    public long getId() { return id; }

    /**
     * idのセッター
     * @param id DB上のID
     */
    public void setId(long id) { this.id = id; }

    /**
     * タイトルのゲッター
     * @return レシピ名
     */
    public String getTitle() { return title; }

    /**
     * URLのゲッター
     * @return 参照URL
     */
    public String getUrl() { return url; }

    /**
     * 食材のゲッター
     * @return 使用食材のリスト(内部参照そのまま)
     */
    public ArrayList<Ingredient> getIngredients() { return ingredients; }

    /**
     * 指定した食材の分量を返すメソッド
     * @param ingredient このレシピの使用食材
     * @return 分量。指定されていなければ {@link Quantity#EMPTY}
     */
    public Quantity getQuantity(Ingredient ingredient) {
        return quantities.getOrDefault(ingredient.getName(), Quantity.EMPTY);
    }

    /**
     * レシピカテゴリーを返すメソッド(コンストラクタで空なら OTHER が入るため、必ず1件以上ある)
     * 返り値は防御的コピーなので、変更しても本オブジェクトには影響しない。
     * @return レシピカテゴリー集合のコピー
     */
    public EnumSet<RecipeCategory> getCategories() {
        return EnumSet.copyOf(categories);
    }
}
