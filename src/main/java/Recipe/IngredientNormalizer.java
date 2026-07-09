package main.java.Recipe;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 食材名の表記ゆれ(同じ食材が違う書き方で複数登録されている状態)を
 * AIに検出させ、正規名へ統一するためのドメイン処理。
 *
 * 役割は3つ:
 *  1. {@link #buildPrompt(List)} … AIへ送る検出プロンプトを組み立てる
 *  2. {@link #parseGroups(String)} … AIが返したJSONを {@link VariantGroup} のリストへ変換する
 *  3. {@link #apply(List, IngredientMaster, AllRecipeList)} … 検出結果を食材マスタとレシピへ反映する
 *
 * AIの呼び出し自体は {@code RecipeAIService.detectIngredientVariants} が担当する。
 * (本クラスはAI実装に依存せず、文字列の入出力だけを扱う)
 */
public final class IngredientNormalizer {

    /**
     * 1つの表記ゆれグループ。{@code canonical} に統一し、{@code variants} を吸収する。
     */
    public static final class VariantGroup {
        /** 統一後の正規名 */
        public final String canonical;
        /** canonical に統一すべき別表記の一覧(canonical 自身は含めない) */
        public final List<String> variants;

        /**
         * @param canonical 統一後の正規名
         * @param variants  吸収される別表記のリスト
         */
        public VariantGroup(String canonical, List<String> variants) {
            this.canonical = canonical;
            this.variants = variants;
        }
    }

    /** 統一適用の結果サマリ */
    public static final class ApplyResult {
        /** 統合された別表記の数(削除された食材名の数) */
        public final int mergedNames;
        /** 食材が書き換えられたレシピの件数 */
        public final int updatedRecipes;

        /**
         * @param mergedNames    統合された別表記の数
         * @param updatedRecipes 更新されたレシピ件数
         */
        public ApplyResult(int mergedNames, int updatedRecipes) {
            this.mergedNames = mergedNames;
            this.updatedRecipes = updatedRecipes;
        }
    }

    private IngredientNormalizer() {}

    /**
     * 表記ゆれ検出用のプロンプトを組み立てる。
     * 既存の食材名リストだけを対象とし、正規名は必ずリスト内の表記から選ばせる。
     * @param names 現在登録されている全食材名
     * @return AIへ送るプロンプト
     */
    public static String buildPrompt(List<String> names) {
        StringBuilder sb = new StringBuilder();
        sb.append("あなたは日本の料理データを整理する専門家です。\n");
        sb.append("以下は食材マスタに登録されている食材名の一覧です。\n");
        sb.append("この中から「同じ食材なのに表記が違うだけ」のもの(表記ゆれ)を見つけてグループ化してください。\n\n");
        sb.append("【食材名一覧】\n");
        for (String n : names) {
            sb.append("- ").append(n).append("\n");
        }
        sb.append("\n【判定ルール】\n");
        sb.append("・表記ゆれの例: 「鶏もも」と「鶏もも肉」、「玉ねぎ」と「たまねぎ」と「タマネギ」、「人参」と「にんじん」\n");
        sb.append("・送り仮名/漢字かな/全半角/末尾の『肉』有無などの違いだけのものは同一とみなす\n");
        sb.append("・別の食材(例: 「豚もも」と「鶏もも」、「ねぎ」と「玉ねぎ」)は絶対に同じグループにしない\n");
        sb.append("・各グループの canonical(正規名)は、必ず一覧の中の表記から最も一般的なものを1つ選ぶ\n");
        sb.append("・variants には、その canonical に統一すべき別表記だけを入れる(canonical 自身は入れない)\n");
        sb.append("・variants も必ず一覧に存在する表記だけにする。新しい食材名を作らない\n");
        sb.append("・表記ゆれが1つも無ければ groups を空配列にする\n");
        sb.append("・前置き・補足・Markdown・コードフェンスは一切禁止\n\n");
        sb.append("【出力形式】以下のJSONオブジェクトのみを返す。\n");
        sb.append("{\"groups\":[{\"canonical\":\"正規名\",\"variants\":[\"別表記1\",\"別表記2\"]}]}\n");
        return sb.toString();
    }

    /**
     * AIが返したJSON文字列を {@link VariantGroup} のリストへ変換する。
     * 期待形式: {@code {"groups":[{"canonical":"..","variants":["..",".."]}]}}。
     * 解析できない/groups が無い場合は空リストを返す。
     * @param json AIの応答(クリーンなJSON文字列)
     * @return 表記ゆれグループのリスト(空可)
     */
    public static List<VariantGroup> parseGroups(String json) {
        List<VariantGroup> result = new ArrayList<>();
        if (json == null) return result;

        int gIdx = json.indexOf("\"groups\"");
        if (gIdx < 0) return result;
        int arrStart = json.indexOf("[", gIdx);
        if (arrStart < 0) return result;
        int arrEnd = findMatchingBracket(json, arrStart, '[', ']');
        if (arrEnd < 0) return result;

        int pos = arrStart + 1;
        while (pos < arrEnd) {
            int objStart = json.indexOf('{', pos);
            if (objStart < 0 || objStart >= arrEnd) break;
            int objEnd = findMatchingBracket(json, objStart, '{', '}');
            if (objEnd < 0 || objEnd > arrEnd) break;

            String obj = json.substring(objStart, objEnd + 1);
            String canonical = extractString(obj, "canonical");
            List<String> variants = extractStringArray(obj, "variants");
            if (canonical != null && !canonical.isEmpty() && !variants.isEmpty()) {
                result.add(new VariantGroup(canonical, variants));
            }
            pos = objEnd + 1;
        }
        return result;
    }

    /**
     * 検出結果を食材マスタとレシピへ反映する。
     * 1) マスタ: 各 variant を削除し、canonical に集約(順序は維持)
     * 2) レシピ: variant を使っているレシピの食材名を canonical へ置換(重複は除去)
     * AIの幻覚に備え、canonical も variant も「現在マスタに存在する名前」のみ採用する。
     * @param groups     AIが検出した表記ゆれグループ
     * @param master     食材マスタ
     * @param recipeList 全レシピ
     * @return 適用結果(統合数・更新レシピ数)
     */
    public static ApplyResult apply(List<VariantGroup> groups, IngredientMaster master, AllRecipeList recipeList) {
        List<Ingredient> current = master.getAllIngredients();
        Set<String> existing = new HashSet<>();
        for (Ingredient ing : current) existing.add(ing.getName());

        // variant -> canonical のマップを、実在する名前だけで構築
        Map<String, String> toCanonical = new LinkedHashMap<>();
        for (VariantGroup g : groups) {
            if (g.canonical == null || !existing.contains(g.canonical)) continue;
            for (String v : g.variants) {
                if (v == null) continue;
                String name = v.trim();
                if (name.isEmpty() || name.equals(g.canonical)) continue;
                if (!existing.contains(name)) continue;       // 実在しない別表記は無視
                if (toCanonical.containsKey(name)) continue;   // 重複指定はスキップ
                toCanonical.put(name, g.canonical);
            }
        }
        if (toCanonical.isEmpty()) return new ApplyResult(0, 0);

        // === 1. マスタを再構築(variant を畳み込み、順序を維持) ===
        Map<String, Ingredient> byName = new HashMap<>();
        for (Ingredient ing : current) byName.put(ing.getName(), ing);

        List<Ingredient> rebuilt = new ArrayList<>();
        Set<String> added = new HashSet<>();
        for (Ingredient ing : current) {
            String name = ing.getName();
            String canon = toCanonical.getOrDefault(name, name);
            if (!added.add(canon)) continue; // 既に追加済み(canonical重複)はスキップ
            Ingredient canonIng = byName.get(canon);
            rebuilt.add(canonIng != null ? canonIng : new Ingredient(canon, ing.getCategory()));
        }
        master.replaceAll(rebuilt);

        // === 2. レシピの食材名を置換 ===
        Map<String, Ingredient> masterAfter = new HashMap<>();
        for (Ingredient ing : master.getAllIngredients()) masterAfter.put(ing.getName(), ing);

        int updatedRecipes = 0;
        for (Recipe r : new ArrayList<>(recipeList.getRecipeList())) {
            ArrayList<Ingredient> newIngs = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            boolean changed = false;
            for (Ingredient ing : r.getIngredients()) {
                String name = ing.getName();
                String canon = toCanonical.getOrDefault(name, name);
                if (!canon.equals(name)) changed = true;
                if (!seen.add(canon)) {
                    changed = true; // 同一レシピ内で重複する食材になったので除去
                    continue;
                }
                Ingredient resolved = masterAfter.get(canon);
                newIngs.add(resolved != null ? resolved : new Ingredient(canon, ing.getCategory()));
            }
            if (changed) {
                EnumSet<RecipeCategory> cats = r.getCategories();
                Recipe updated = new Recipe(r.getTitle(), r.getUrl(), newIngs, cats);
                updated.setId(r.getId());
                recipeList.updateRecipe(updated);
                updatedRecipes++;
            }
        }

        return new ApplyResult(toCanonical.size(), updatedRecipes);
    }

    // ---- 簡易JSONパーサ(本プロジェクトの他クラスと同様、外部ライブラリ非依存) ----

    /**
     * "key":"value" 形式の文字列値を抽出する(unescape済みで返す)。
     * @param obj 対象JSON
     * @param key キー名
     * @return 値。見つからなければ null
     */
    private static String extractString(String obj, String key) {
        int keyIdx = obj.indexOf("\"" + key + "\"");
        if (keyIdx < 0) return null;
        int colon = obj.indexOf(":", keyIdx);
        if (colon < 0) return null;
        int valStart = obj.indexOf("\"", colon);
        if (valStart < 0) return null;
        valStart++;
        int valEnd = findUnescapedQuote(obj, valStart);
        if (valEnd < 0) return null;
        return jsonUnescape(obj.substring(valStart, valEnd)).trim();
    }

    /**
     * "key":["v1","v2",…] 形式の文字列配列を抽出する。
     * @param obj 対象JSON
     * @param key キー名
     * @return 値リスト(キーが無ければ空)
     */
    private static List<String> extractStringArray(String obj, String key) {
        List<String> items = new ArrayList<>();
        int keyIdx = obj.indexOf("\"" + key + "\"");
        if (keyIdx < 0) return items;
        int arrStart = obj.indexOf("[", keyIdx);
        if (arrStart < 0) return items;
        int arrEnd = findMatchingBracket(obj, arrStart, '[', ']');
        if (arrEnd < 0) return items;
        int pos = arrStart + 1;
        while (pos < arrEnd) {
            int strStart = obj.indexOf("\"", pos);
            if (strStart < 0 || strStart >= arrEnd) break;
            int strEnd = findUnescapedQuote(obj, strStart + 1);
            if (strEnd < 0 || strEnd > arrEnd) break;
            items.add(jsonUnescape(obj.substring(strStart + 1, strEnd)).trim());
            pos = strEnd + 1;
        }
        return items;
    }

    /**
     * pos 以降でエスケープされていない最初の '"' を探す。
     * @param s   走査文字列
     * @param pos 開始位置
     * @return 位置。見つからなければ -1
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
     * 開き括弧に対応する閉じ括弧を、文字列リテラルを除外して探す。
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
     * JSON文字列値内のエスケープを実体へ戻す。
     * @param s エスケープされた文字列
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
