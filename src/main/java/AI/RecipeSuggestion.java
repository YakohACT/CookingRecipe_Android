package main.java.AI;

import java.util.List;

/**
 * AIが提案したレシピ(料理名・分量付きの食材一覧・カテゴリー)
 * AI パッケージはレシピのドメインクラスに依存しないため、文字列だけで保持する
 */
public final class RecipeSuggestion {

    /** 料理名(検出できなかった場合は空文字) */
    public final String title;
    /** 食材一覧(AIが返した順) */
    public final List<Item> ingredients;
    /** AIが選んだカテゴリーのラベル(候補として渡したラベルのどれか。選べなかった場合は空) */
    public final List<String> categories;

    /**
     * @param title       料理名
     * @param ingredients 食材一覧
     * @param categories  カテゴリーのラベル一覧
     */
    public RecipeSuggestion(String title, List<Item> ingredients, List<String> categories) {
        this.title = title;
        this.ingredients = ingredients;
        this.categories = categories;
    }

    /** 食材1つ分の提案 */
    public static final class Item {
        /** 食材名 */
        public final String name;
        /** 数量(例: "200", "1/2"。数値が無い場合は空文字) */
        public final String amount;
        /** 単位(例: "g", "大さじ", "適量"。無い場合は空文字) */
        public final String unit;

        /**
         * @param name   食材名
         * @param amount 数量
         * @param unit   単位
         */
        public Item(String name, String amount, String unit) {
            this.name = name;
            this.amount = amount;
            this.unit = unit;
        }
    }
}
