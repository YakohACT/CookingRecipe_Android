package main.java.Recipe;

import java.util.Objects;

/**
 * 食材データを管理するクラス。
 * 永続化は SQLite + database.csv 経由で行うため Java シリアライゼーションは不要。
 */
public class Ingredient {
    private String name;
    private IngredientCategory category;

    /**
     * コンストラクタ
     * @param name 食材名
     * @param category 食材カテゴリ
     */
    public Ingredient(String name, IngredientCategory category) {
        this.name = name;
        this.category = category;
    }

    /**
     * @return 食材名
     */
    public String getName() { return name; }

    /**
     * @return 食材カテゴリー
     */
    public IngredientCategory getCategory() { return category; }

    /**
     * リスト表示時にラベル文字列として食材名をそのまま返す。
     * @return 食材名
     */
    @Override
    public String toString() { return name; }

    /**
     * 名前のみで等価判定する(カテゴリーが違っても同じ名前なら同一とみなす)。
     * @param o 比較対象オブジェクト
     * @return 名前が一致すれば true
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Ingredient that = (Ingredient) o;
        return Objects.equals(name, that.name);
    }

    /**
     * 名前のみのハッシュコード(equals と整合)。
     * @return 食材名のハッシュ
     */
    @Override
    public int hashCode() { return Objects.hash(name); }
}
