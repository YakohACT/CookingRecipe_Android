package main.java.Recipe;

import main.java.AI.RecipeAIService;
import main.java.Util.JsonUtil;

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
 * AIに検出させ、正規名へ統一するためのドメイン処理をするクラス
 *
 *  {@link #detectVariants(RecipeAIService, List)}->検出プロンプトをAIへ送り、返ったJSONを {@link VariantGroup} のリストへ変換する
 *  {@link #filterExisting(List, IngredientMaster)}->マスタに実在する食材名だけに絞り込む(確認画面の表示用)
 *  {@link #apply(List, IngredientMaster, AllRecipeList)}->検出結果を食材マスタとレシピへ反映する
 */
public final class IngredientNormalizer {

    /**
     * 1つの表記ゆれグループ。{@code canonical} に統一し、{@code variants} を吸収する
     */
    public static final class VariantGroup {
        /** 統一後の正規名 */
        public final String canonical;
        /** canonical に統一すべき別表記の一覧 */
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
     * AIに食材名の表記ゆれグループを検出させるメソッド
     * @param ai    設定済みのAIサービス
     * @param names 現在登録されている全ての食材名
     * @return 表記ゆれグループのリスト(表記ゆれが無ければ空)
     * @throws Exception AI呼び出し失敗時 / AI未設定時
     */
    public static List<VariantGroup> detectVariants(RecipeAIService ai, List<String> names) throws Exception {
        return parseGroups(ai.chat(buildPrompt(names)));
    }

    /**
     * 表記ゆれ検出用のプロンプトを組み立てるメソッド
     * @param names 現在登録されている全ての食材名
     * @return プロンプト
     */
    private static String buildPrompt(List<String> names) {
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
     * AIが返したJSON文字列を {@link VariantGroup} のリストへ変換する
     * 期待形式: {@code {"groups":[{"canonical":"..","variants":["..",".."]}]}}。
     * 解析できない/groups が無い場合は空リストを返す。
     * @param json AIからの回答
     * @return 表記ゆれグループのリスト
     */
    private static List<VariantGroup> parseGroups(String json) {
        List<VariantGroup> result = new ArrayList<>();
        for (String obj : JsonUtil.extractObjectArray(json, "groups")) {
            String canonical = JsonUtil.extractString(obj, "canonical");
            List<String> variants = new ArrayList<>();
            for (String v : JsonUtil.extractStringArray(obj, "variants")) variants.add(v.trim());
            if (canonical != null && !canonical.trim().isEmpty() && !variants.isEmpty()) {
                result.add(new VariantGroup(canonical.trim(), variants));
            }
        }
        return result;
    }

    /**
     * AIが返したグループを、現在のマスタに実在する食材名だけに絞り込むメソッド
     * canonical が実在しないグループと、実在しない/canonical と同じ別表記は除外する
     * @param groups AIが検出した表記ゆれグループ
     * @param master 食材マスタ
     * @return 統一を適用できるグループのリスト(別表記が1つも残らないグループは含まない)
     */
    public static List<VariantGroup> filterExisting(List<VariantGroup> groups, IngredientMaster master) {
        Set<String> existing = new HashSet<>();
        for (Ingredient ing : master.getAllIngredients()) existing.add(ing.getName());

        List<VariantGroup> result = new ArrayList<>();
        for (VariantGroup g : groups) {
            if (g.canonical == null || !existing.contains(g.canonical)) continue;
            List<String> variants = new ArrayList<>();
            for (String v : g.variants) {
                if (v != null && existing.contains(v) && !v.equals(g.canonical) && !variants.contains(v)) {
                    variants.add(v);
                }
            }
            if (!variants.isEmpty()) result.add(new VariantGroup(g.canonical, variants));
        }
        return result;
    }

    /**
     * 検出結果を食材マスタとレシピへ反映するメソッド
     * @param groups     AIが検出した表記ゆれグループ
     * @param master     食材マスタ
     * @param recipeList 全レシピ
     * @return 適用結果
     */
    public static ApplyResult apply(List<VariantGroup> groups, IngredientMaster master, AllRecipeList recipeList) {
        // variant -> canonical のマップを、実在する名前だけで構築(重複指定は先勝ち)
        Map<String, String> toCanonical = new LinkedHashMap<>();
        for (VariantGroup g : filterExisting(groups, master)) {
            for (String v : g.variants) toCanonical.putIfAbsent(v, g.canonical);
        }
        if (toCanonical.isEmpty()) return new ApplyResult(0, 0);

        List<Ingredient> current = master.getAllIngredients();

        // マスタを再構築
        Map<String, Ingredient> byName = new HashMap<>();
        for (Ingredient ing : current) byName.put(ing.getName(), ing);

        List<Ingredient> rebuilt = new ArrayList<>();
        Set<String> added = new HashSet<>();
        for (Ingredient ing : current) {
            String name = ing.getName();
            String canon = toCanonical.getOrDefault(name, name);
            if (!added.add(canon)) continue; // 追加済みはスキップ
            Ingredient canonIng = byName.get(canon);
            rebuilt.add(canonIng != null ? canonIng : new Ingredient(canon, ing.getCategory()));
        }
        master.replaceAll(rebuilt);

        // レシピの食材名を置換
        Map<String, Ingredient> masterAfter = new HashMap<>();
        for (Ingredient ing : master.getAllIngredients()) masterAfter.put(ing.getName(), ing);

        int updatedRecipes = 0;
        for (Recipe r : new ArrayList<>(recipeList.getRecipeList())) {
            ArrayList<Ingredient> newIngs = new ArrayList<>();
            Map<String, Quantity> newQuantities = new HashMap<>();
            Set<String> seen = new HashSet<>();
            boolean changed = false;
            for (Ingredient ing : r.getIngredients()) {
                String name = ing.getName();
                String canon = toCanonical.getOrDefault(name, name);
                if (!canon.equals(name)) changed = true;
                if (!seen.add(canon)) {
                    changed = true; // 同一レシピ内で重複する食材になったので除去(分量は先に出てきた方を残す)
                    continue;
                }
                Ingredient resolved = masterAfter.get(canon);
                newIngs.add(resolved != null ? resolved : new Ingredient(canon, ing.getCategory()));
                newQuantities.put(canon, r.getQuantity(ing));
            }
            if (changed) {
                EnumSet<RecipeCategory> cats = r.getCategories();
                Recipe updated = new Recipe(r.getTitle(), r.getUrl(), newIngs, newQuantities, cats);
                updated.setId(r.getId());
                recipeList.updateRecipe(updated);
                updatedRecipes++;
            }
        }

        return new ApplyResult(toCanonical.size(), updatedRecipes);
    }
}
