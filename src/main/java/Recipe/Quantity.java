package main.java.Recipe;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * レシピ内の食材1つ分の分量(数値 + 単位)を表す値クラス
 * 表示は単位の種類に応じて「5g」「1つ」「大さじ3」「適量」のように組み立てる
 * (List.of / Set.of は Android API 30 未満に無いため Arrays.asList で組み立てる)
 */
public final class Quantity {

    /** 分量の指定なし */
    public static final Quantity EMPTY = new Quantity("", "");

    /** 単位入力欄の候補 */
    public static final List<String> COMMON_UNITS = Collections.unmodifiableList(Arrays.asList(
            "g", "kg", "ml", "L", "cc", "個", "つ", "本", "枚", "切れ", "片", "かけ", "束", "株", "房",
            "袋", "パック", "缶", "合", "カップ", "大さじ", "小さじ", "適量", "少々", "ひとつまみ", "お好みで"));

    /** 数値の前に付ける単位(「大さじ3」のように表示する) */
    private static final Set<String> PREFIX_UNITS = setOf("大さじ", "小さじ", "カップ");

    /** 数値を持たない単位(「適量」のように単位だけ表示する) */
    private static final Set<String> UNIT_ONLY = setOf("適量", "少々", "お好みで");

    /** 数値として扱う形式: 200 / 0.5 / 1/2 / 1~2 */
    private static final String NUMBER = "\\d+(?:[./]\\d+)?(?:\\s*[~\\-]\\s*\\d+(?:[./]\\d+)?)?";
    private static final Pattern NUMBER_ONLY = Pattern.compile(NUMBER);
    private static final Pattern PREFIX_FORM = Pattern.compile("(大さじ|小さじ|カップ)\\s*(" + NUMBER + ")");
    private static final Pattern SUFFIX_FORM = Pattern.compile("(" + NUMBER + ")\\s*(.*)");

    /** 数値部分(例: "200", "1/2")。数値の無い分量は空文字 */
    private final String amount;
    /** 単位(例: "g", "大さじ", "適量")。単位の無い分量は空文字 */
    private final String unit;

    private Quantity(String amount, String unit) {
        this.amount = amount;
        this.unit = unit;
    }

    /**
     * 変更不可の Set を作るメソッド(Set.of の代わり)
     * @param values 要素
     * @return 変更不可の Set
     */
    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    /**
     * 数値と単位から分量を作るメソッド
     * 前後の空白と全角数字を整え、数値欄に「大さじ3」のような単位付きの文字列が
     * 入っていた場合は {@link #parse(String)} で数値と単位に分け直す
     * @param amount 数値(null可)
     * @param unit   単位(null可)
     * @return 分量。どちらも空なら {@link #EMPTY}
     */
    public static Quantity of(String amount, String unit) {
        String a = toHalfWidth(amount == null ? "" : amount.trim());
        String u = unit == null ? "" : unit.trim();
        if (UNIT_ONLY.contains(u)) a = "";
        if (!a.isEmpty() && !NUMBER_ONLY.matcher(a).matches()) {
            return parse(a + u);
        }
        if (a.isEmpty() && u.isEmpty()) return EMPTY;
        return new Quantity(a, u);
    }

    /**
     * 「200g」「1/2個」「大さじ3」「適量」のような文字列を数値と単位に分けるメソッド
     * 数値が見つからない場合は文字列全体を単位として扱う
     * @param text 分量の文字列(null可)
     * @return 分量。空文字なら {@link #EMPTY}
     */
    public static Quantity parse(String text) {
        String t = toHalfWidth(text == null ? "" : text.trim());
        if (t.isEmpty()) return EMPTY;
        Matcher prefix = PREFIX_FORM.matcher(t);
        if (prefix.matches()) return new Quantity(prefix.group(2), prefix.group(1));
        Matcher suffix = SUFFIX_FORM.matcher(t);
        if (suffix.matches()) return new Quantity(suffix.group(1), suffix.group(2).trim());
        return new Quantity("", t);
    }

    /**
     * 全角の数字と記号(．／～〜)を半角にそろえるメソッド
     * @param s 元の文字列
     * @return 変換後の文字列
     */
    private static String toHalfWidth(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '０' && c <= '９') sb.append((char) (c - '０' + '0'));
            else if (c == '．') sb.append('.');
            else if (c == '／') sb.append('/');
            else if (c == '～' || c == '〜') sb.append('~');
            else sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 数値のゲッター
     * @return 数値部分(数値の無い分量は空文字)
     */
    public String getAmount() { return amount; }

    /**
     * 単位のゲッター
     * @return 単位(単位の無い分量は空文字)
     */
    public String getUnit() { return unit; }

    /**
     * 分量が指定されていないかを返すメソッド
     * @return 数値も単位も空なら true
     */
    public boolean isEmpty() { return amount.isEmpty() && unit.isEmpty(); }

    /**
     * 表示用の文字列を返すメソッド(例: "5g", "1つ", "大さじ3", "適量")
     * @return 表示用の分量。指定なしは空文字
     */
    @Override
    public String toString() {
        if (amount.isEmpty()) return unit;
        if (PREFIX_UNITS.contains(unit)) return unit + amount;
        return amount + unit;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Quantity)) return false;
        Quantity that = (Quantity) o;
        return amount.equals(that.amount) && unit.equals(that.unit);
    }

    @Override
    public int hashCode() { return Objects.hash(amount, unit); }
}
