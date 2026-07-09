package main.java.Recipe;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * レシピの JSON 入出力を担うユーティリティ。
 * 外部ライブラリに依存せず、純Javaで JSON を組み立て・解析する。
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
 *       "ingredients": ["鶏もも", "たまご", "玉ねぎ"]
 *     }
 *   ]
 * }
 * </pre>
 */
public final class RecipeIO {

    /** ファイルフォーマットのバージョン。後方互換が壊れる変更時にインクリメント */
    public static final int FORMAT_VERSION = 1;

    private RecipeIO() {}

    /**
     * レシピ一覧を JSON 文字列に書き出す。
     * @param recipes 出力対象のレシピ
     * @return JSON 文字列(改行・インデント付き)
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
     * 1件のレシピを JSON オブジェクトとしてシリアライズする。
     * @param r      レシピ
     * @param indent 各行の先頭インデント
     * @return JSON 形式の文字列
     */
    private static String serializeRecipe(Recipe r, String indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append("{\n");
        sb.append(indent).append("  \"title\": \"").append(jsonEscape(r.getTitle())).append("\",\n");
        sb.append(indent).append("  \"url\": \"").append(jsonEscape(r.getUrl())).append("\",\n");
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
            sb.append("\"").append(jsonEscape(r.getIngredients().get(k).getName())).append("\"");
        }
        sb.append("]\n");
        sb.append(indent).append("}");
        return sb.toString();
    }

    /**
     * JSON 文字列からレシピ一覧を復元する。
     * 食材名はマスタから解決し、見つからない場合は OTHER カテゴリの暫定 Ingredient を作る。
     * 不明なカテゴリー値は無視する。
     * @param json             読み込む JSON 文字列
     * @param ingredientMaster 食材名解決用のマスタ
     * @return 復元されたレシピのリスト(空可)
     */
    public static List<Recipe> parseFromJson(String json, IngredientMaster ingredientMaster) {
        List<Recipe> result = new ArrayList<>();
        if (json == null) return result;

        int recipesIdx = json.indexOf("\"recipes\"");
        if (recipesIdx < 0) return result;
        int arrStart = json.indexOf("[", recipesIdx);
        if (arrStart < 0) return result;
        int arrEnd = findMatchingBracket(json, arrStart, '[', ']');
        if (arrEnd < 0) return result;

        int pos = arrStart + 1;
        while (pos < arrEnd) {
            int objStart = json.indexOf('{', pos);
            if (objStart < 0 || objStart >= arrEnd) break;
            int objEnd = findMatchingBracket(json, objStart, '{', '}');
            if (objEnd < 0 || objEnd > arrEnd) break;
            Recipe r = parseRecipe(json.substring(objStart, objEnd + 1), ingredientMaster);
            if (r != null) result.add(r);
            pos = objEnd + 1;
        }
        return result;
    }

    /**
     * 1件のレシピ JSON オブジェクト文字列を Recipe に変換する。
     * @param obj JSON オブジェクト文字列
     * @param im  食材マスタ
     * @return 復元された Recipe (タイトル必須)。タイトルが取れなければ null
     */
    private static Recipe parseRecipe(String obj, IngredientMaster im) {
        String title = extractJsonString(obj, "title");
        if (title == null || title.isEmpty()) return null;
        String url = extractJsonString(obj, "url");
        if (url == null) url = "";

        EnumSet<RecipeCategory> cats = EnumSet.noneOf(RecipeCategory.class);
        for (String name : extractJsonStringArray(obj, "categories")) {
            try { cats.add(RecipeCategory.valueOf(name)); } catch (IllegalArgumentException ignore) {}
        }

        ArrayList<Ingredient> ings = new ArrayList<>();
        for (String name : extractJsonStringArray(obj, "ingredients")) {
            ings.add(lookupIngredient(im, name));
        }
        return new Recipe(title, url, ings, cats);
    }

    /**
     * 食材マスタから名前一致で Ingredient を引き、無ければ OTHER カテゴリで
     * マスタ + database.csv に自動追加してから返す。
     * @param im   食材マスタ
     * @param name 食材名
     * @return 解決された Ingredient
     */
    private static Ingredient lookupIngredient(IngredientMaster im, String name) {
        if (im != null) {
            for (Ingredient ing : im.getAllIngredients()) {
                if (ing.getName().equals(name)) return ing;
            }
            // マスタに無い場合は OTHER で自動追加(database.csv にも書き込む)
            Ingredient added = im.addIngredient(name);
            if (added != null) return added;
        }
        return new Ingredient(name, IngredientCategory.OTHER);
    }

    /**
     * "key":"value" 形式の文字列値を抽出する(unescape 済みで返す)。
     * @param obj 検索対象 JSON
     * @param key キー名
     * @return 値文字列。見つからなければ null
     */
    private static String extractJsonString(String obj, String key) {
        int keyIdx = obj.indexOf("\"" + key + "\"");
        if (keyIdx < 0) return null;
        int colon = obj.indexOf(":", keyIdx);
        if (colon < 0) return null;
        int valStart = obj.indexOf("\"", colon);
        if (valStart < 0) return null;
        valStart++;
        int valEnd = findUnescapedQuote(obj, valStart);
        if (valEnd < 0) return null;
        return jsonUnescape(obj.substring(valStart, valEnd));
    }

    /**
     * "key":["v1","v2",…] 形式の文字列配列を抽出する。
     * @param obj 検索対象 JSON
     * @param key キー名
     * @return 値の文字列配列(キーが無ければ空配列)
     */
    private static String[] extractJsonStringArray(String obj, String key) {
        int keyIdx = obj.indexOf("\"" + key + "\"");
        if (keyIdx < 0) return new String[0];
        int arrStart = obj.indexOf("[", keyIdx);
        if (arrStart < 0) return new String[0];
        int arrEnd = findMatchingBracket(obj, arrStart, '[', ']');
        if (arrEnd < 0) return new String[0];
        ArrayList<String> items = new ArrayList<>();
        int pos = arrStart + 1;
        while (pos < arrEnd) {
            int strStart = obj.indexOf("\"", pos);
            if (strStart < 0 || strStart >= arrEnd) break;
            int strEnd = findUnescapedQuote(obj, strStart + 1);
            if (strEnd < 0 || strEnd > arrEnd) break;
            items.add(jsonUnescape(obj.substring(strStart + 1, strEnd)));
            pos = strEnd + 1;
        }
        return items.toArray(new String[0]);
    }

    /**
     * pos 以降でエスケープされていない最初の '"' を探す。
     * @param s   走査文字列
     * @param pos 走査開始位置
     * @return マッチ位置。見つからなければ -1
     */
    private static int findUnescapedQuote(String s, int pos) {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == '\\') { pos += 2; continue; }
            if (c == '"') return pos;
            pos++;
        }
        return -1;
    }

    /**
     * 開き括弧と対応する閉じ括弧を、文字列リテラルを除外した上で探す。
     * @param s     走査文字列
     * @param start 開き括弧の位置
     * @param open  開き括弧文字
     * @param close 閉じ括弧文字
     * @return 対応する閉じ括弧の位置。見つからなければ -1
     */
    private static int findMatchingBracket(String s, int start, char open, char close) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * Java 文字列を JSON 文字列値として埋め込めるようエスケープする。
     * @param s 元文字列(null 可)
     * @return エスケープ後の文字列
     */
    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * JSON 文字列値内のエスケープを実体に戻す。
     * @param s エスケープされた JSON 値
     * @return 復元後の文字列
     */
    private static String jsonUnescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '\\': sb.append('\\'); i++; break;
                    case '"':  sb.append('"');  i++; break;
                    case '/':  sb.append('/');  i++; break;
                    case 'n':  sb.append('\n'); i++; break;
                    case 'r':  sb.append('\r'); i++; break;
                    case 't':  sb.append('\t'); i++; break;
                    case 'b':  sb.append('\b'); i++; break;
                    case 'f':  sb.append('\f'); i++; break;
                    case 'u':
                        if (i + 5 < s.length()) {
                            try {
                                int code = Integer.parseInt(s.substring(i + 2, i + 6), 16);
                                sb.append((char) code);
                                i += 5;
                            } catch (NumberFormatException e) { sb.append(c); }
                        } else sb.append(c);
                        break;
                    default: sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
