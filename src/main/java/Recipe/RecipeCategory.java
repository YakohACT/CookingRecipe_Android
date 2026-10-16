package main.java.Recipe;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * レシピのカテゴリーを定義する列挙型
 */
public enum RecipeCategory {
    RICE("ごはんもの"),
    MEAT("肉料理"),
    VEGETABLE("野菜料理"),
    EGG("卵料理"),
    NOODLE("麺類"),
    SOUP("汁物"),
    BREAD("パン"),
    SEAFOOD("魚介料理"),
    HOTPOT("鍋料理"),
    FRIED("揚げ物"),
    WASHOKU("和食"),
    YOSHOKU("洋食"),
    CHUKA("中華料理"),
    SWEETS("お菓子"),
    OTHER("その他");

    private final String displayName;

    /**
     * @param displayName UI表示用の日本語ラベル
     */
    RecipeCategory(String displayName) {
        this.displayName = displayName;
    }

    /**
     * @return UI表示用の日本語ラベル
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * コンボボックスで日本語表示するためdisplayNameを返す
     * @return UI表示用の日本語ラベル
     */
    @Override
    public String toString() {
        return displayName;
    }

    /**
     * 全カテゴリーの日本語ラベルを定義順で返すメソッド(AIへ渡す候補一覧に使う)
     * @return 日本語ラベルのリスト
     */
    public static List<String> displayNames() {
        List<String> names = new ArrayList<>();
        for (RecipeCategory c : values()) names.add(c.displayName);
        return names;
    }

    /**
     * 日本語ラベル(「和食」)または列挙名(「WASHOKU」)からカテゴリーを引くメソッド
     * 前後の空白とカギカッコ類は無視する
     * @param label ラベル(null可)
     * @return 該当するカテゴリー。見つからなければ null
     */
    public static RecipeCategory fromLabel(String label) {
        if (label == null) return null;
        String s = label.replaceAll("[\\[\\]【】「」]", "").trim();
        for (RecipeCategory c : values()) {
            if (c.displayName.equals(s) || c.name().equalsIgnoreCase(s)) return c;
        }
        return null;
    }

    /**
     * ラベルの一覧をカテゴリーの集合に変換するメソッド(AIが選んだカテゴリーの取り込みに使う)
     * 該当しないラベルは無視し、他のカテゴリーがあるときは「その他」を外す
     * @param labels ラベルの一覧
     * @return カテゴリーの集合(該当なしなら空)
     */
    public static EnumSet<RecipeCategory> fromLabels(List<String> labels) {
        EnumSet<RecipeCategory> result = EnumSet.noneOf(RecipeCategory.class);
        for (String label : labels) {
            RecipeCategory c = fromLabel(label);
            if (c != null) result.add(c);
        }
        if (result.size() > 1) result.remove(OTHER);
        return result;
    }
}
