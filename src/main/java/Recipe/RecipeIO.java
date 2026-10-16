package main.java.Recipe;

import main.java.Util.JsonUtil;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * レシピのJSON入出力を担うユーティリティ
 *
 * 出力フォーマット:
 * <pre>
 * {
 *   "version": 1,
 *   "exportedAt": "2026-05-07T12:34:56",
 *   "recipes": [
 *     {
 *       "title": "親子丼",
 *       "url": "https://...",
 *       "categories": ["RICE", "EGG", "MEAT", "WASHOKU"],
 *       "ingredients": ["鶏もも", "たまご", "玉ねぎ"],
 *       "quantities": [{"name": "鶏もも", "amount": "200", "unit": "g"}]
 *     }
 *   ]
 * }
 * </pre>
 * quantities は分量を指定した食材だけを持つ。quantities の無い古いファイルも読み込める
 */
public final class RecipeIO {

    /** ファイルフォーマットのバージョン */
    public static final int FORMAT_VERSION = 1;

    private RecipeIO() {}

    /**
     * レシピ一覧を文字列に書き出すメソッド
     * @param recipes 出力対象のレシピ
     * @return JSON 文字列
     */
    public static String exportToJson(List<Recipe> recipes) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": ").append(FORMAT_VERSION).append(",\n");
        sb.append("  \"exportedAt\": \"")
                .append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .append("\",\n");
        sb.append("  \"recipes\": [");
        for (int i = 0; i < recipes.size(); i++) {
            sb.append(i == 0 ? "\n" : ",\n");
            sb.append(serializeRecipe(recipes.get(i), "    "));
        }
        if (!recipes.isEmpty()) sb.append("\n  ");
        sb.append("]\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * 1件のレシピをオブジェクトとしてシリアライズするメソッド
     * @param r      レシピ
     * @param indent 各行の先頭インデント
     * @return JSON 形式の文字列
     */
    private static String serializeRecipe(Recipe r, String indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append("{\n");
        sb.append(indent).append("  \"title\": \"").append(JsonUtil.escape(r.getTitle())).append("\",\n");
        sb.append(indent).append("  \"url\": \"").append(JsonUtil.escape(r.getUrl())).append("\",\n");
        sb.append(indent).append("  \"categories\": [");
        int j = 0;
        for (RecipeCategory cat : r.getCategories()) {
            if (j++ > 0) sb.append(", ");
            sb.append("\"").append(cat.name()).append("\"");
        }
        sb.append("],\n");
        sb.append(indent).append("  \"ingredients\": [");
        for (int k = 0; k < r.getIngredients().size(); k++) {
            if (k > 0) sb.append(", ");
            sb.append("\"").append(JsonUtil.escape(r.getIngredients().get(k).getName())).append("\"");
        }
        sb.append("],\n");
        sb.append(indent).append("  \"quantities\": [");
        int q = 0;
        for (Ingredient ing : r.getIngredients()) {
            Quantity quantity = r.getQuantity(ing);
            if (quantity.isEmpty()) continue;
            if (q++ > 0) sb.append(", ");
            sb.append("{\"name\": \"").append(JsonUtil.escape(ing.getName()))
              .append("\", \"amount\": \"").append(JsonUtil.escape(quantity.getAmount()))
              .append("\", \"unit\": \"").append(JsonUtil.escape(quantity.getUnit())).append("\"}");
        }
        sb.append("]\n");
        sb.append(indent).append("}");
        return sb.toString();
    }

    /**
     * 文字列からレシピ一覧を復元するメソッド
     * @param json             読み込む JSON 文字列
     * @param ingredientMaster 食材名解決用のマスタ
     * @return 復元されたレシピのリスト
     */
    public static List<Recipe> parseFromJson(String json, IngredientMaster ingredientMaster) {
        List<Recipe> result = new ArrayList<>();
        for (String obj : JsonUtil.extractObjectArray(json, "recipes")) {
            Recipe r = parseRecipe(obj, ingredientMaster);
            if (r != null) result.add(r);
        }
        return result;
    }

    /**
     * 文字列をRecipeに変換するメソッド
     * @param obj JSON オブジェクト文字列
     * @param im  食材マスタ
     * @return 復元された Recipe、タイトルが取れなければ null
     */
    private static Recipe parseRecipe(String obj, IngredientMaster im) {
        String title = JsonUtil.extractString(obj, "title");
        if (title == null || title.isEmpty()) return null;
        String url = JsonUtil.extractString(obj, "url");
        if (url == null) url = "";

        EnumSet<RecipeCategory> cats = EnumSet.noneOf(RecipeCategory.class);
        for (String name : JsonUtil.extractStringArray(obj, "categories")) {
            try { cats.add(RecipeCategory.valueOf(name)); } catch (IllegalArgumentException ignore) {}
        }

        ArrayList<Ingredient> ings = new ArrayList<>();
        for (String name : JsonUtil.extractStringArray(obj, "ingredients")) {
            // マスタに無い食材名は OTHER で自動追加(database.csv にも書き込む)
            Ingredient ing = im.addIngredient(name);
            if (ing != null) ings.add(ing);
        }

        Map<String, Quantity> quantities = new LinkedHashMap<>();
        for (String q : JsonUtil.extractObjectArray(obj, "quantities")) {
            String name = JsonUtil.extractString(q, "name");
            if (name == null) continue;
            quantities.put(name.trim(), Quantity.of(JsonUtil.extractString(q, "amount"), JsonUtil.extractString(q, "unit")));
        }
        return new Recipe(title, url, ings, quantities, cats);
    }
}
