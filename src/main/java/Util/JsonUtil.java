package main.java.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * 外部ライブラリを使わずにJSON文字列を扱う簡易ユーティリティ
 * AIのレスポンス解析・レシピのJSON入出力・表記ゆれ検出結果の解析など、
 * アプリ内のJSON処理はすべてこのクラスを経由する
 */
public final class JsonUtil {

    private JsonUtil() {}

    /**
     * Java文字列をJSON文字列値として埋め込めるようエスケープするメソッド
     * @param s 元の文字列(null は空文字として扱う)
     * @return エスケープ後の文字列
     */
    public static String escape(String s) {
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
     * JSON文字列値内のエスケープを実体に戻すメソッド({@code \"} → {@code "} など)
     * @param s エスケープされたJSON文字列値
     * @return 復元後の文字列
     */
    public static String unescape(String s) {
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

    /**
     * "key":"value" 形式の文字列値を取り出すメソッド(最初に見つかったキーが対象)
     * @param json 検索対象のJSON
     * @param key  キー名
     * @return unescape済みの値。キーが無い/値が文字列でない場合は null
     */
    public static String extractString(String json, String key) {
        if (json == null) return null;
        int valStart = findValueStart(json, key, 0);
        if (valStart < 0 || json.charAt(valStart) != '"') return null;
        int valEnd = findUnescapedQuote(json, valStart + 1);
        if (valEnd < 0) return null;
        return unescape(json.substring(valStart + 1, valEnd));
    }

    /**
     * "key":"value" または "key":123 形式の値を文字列として取り出すメソッド(最初に見つかったキーが対象)
     * AIが数量を数値(200)で返した場合にも文字列として受け取るために使う
     * @param json 検索対象のJSON
     * @param key  キー名
     * @return 文字列値は unescape 済みの値、数値/真偽値は表記そのまま。キーが無い/null/配列/オブジェクトの場合は null
     */
    public static String extractScalar(String json, String key) {
        if (json == null) return null;
        int valStart = findValueStart(json, key, 0);
        if (valStart < 0) return null;
        if (json.charAt(valStart) == '"') return extractString(json, key);
        int end = valStart;
        while (end < json.length() && ",}] \t\r\n".indexOf(json.charAt(end)) < 0) end++;
        String literal = json.substring(valStart, end);
        if (literal.isEmpty() || literal.equals("null") || literal.startsWith("{") || literal.startsWith("[")) return null;
        return literal;
    }

    /**
     * "key":["v1","v2",…] 形式の文字列配列を取り出すメソッド
     * @param json 検索対象のJSON
     * @param key  キー名
     * @return unescape済みの値リスト(キーが無い/値が配列でない場合は空)
     */
    public static List<String> extractStringArray(String json, String key) {
        List<String> items = new ArrayList<>();
        if (json == null) return items;
        int arrStart = findValueStart(json, key, 0);
        if (arrStart < 0 || json.charAt(arrStart) != '[') return items;
        int arrEnd = findMatchingBracket(json, arrStart, '[', ']');
        if (arrEnd < 0) return items;
        int pos = arrStart + 1;
        while (pos < arrEnd) {
            int strStart = json.indexOf('"', pos);
            if (strStart < 0 || strStart >= arrEnd) break;
            int strEnd = findUnescapedQuote(json, strStart + 1);
            if (strEnd < 0 || strEnd > arrEnd) break;
            items.add(unescape(json.substring(strStart + 1, strEnd)));
            pos = strEnd + 1;
        }
        return items;
    }

    /**
     * "key":[{…},{…}] 形式のオブジェクト配列から、各オブジェクトの文字列を取り出すメソッド
     * @param json 検索対象のJSON
     * @param key  キー名
     * @return 各要素の "{…}" 部分文字列のリスト(キーが無い/値が配列でない場合は空)
     */
    public static List<String> extractObjectArray(String json, String key) {
        List<String> objects = new ArrayList<>();
        if (json == null) return objects;
        int arrStart = findValueStart(json, key, 0);
        if (arrStart < 0 || json.charAt(arrStart) != '[') return objects;
        int arrEnd = findMatchingBracket(json, arrStart, '[', ']');
        if (arrEnd < 0) return objects;
        int pos = arrStart + 1;
        while (pos < arrEnd) {
            int objStart = json.indexOf('{', pos);
            if (objStart < 0 || objStart >= arrEnd) break;
            int objEnd = findMatchingBracket(json, objStart, '{', '}');
            if (objEnd < 0 || objEnd > arrEnd) break;
            objects.add(json.substring(objStart, objEnd + 1));
            pos = objEnd + 1;
        }
        return objects;
    }

    /**
     * JSON内に出現する "key":"value" の value をすべて収集するメソッド
     * モデル一覧APIのように [{"id":"…"},{"id":"…"}] の "id" を全件取りたい時に使う
     * @param json 検索対象のJSON
     * @param key  キー名
     * @return unescape済みの値リスト(出現順)
     */
    public static List<String> extractAllStrings(String json, String key) {
        List<String> results = new ArrayList<>();
        if (json == null) return results;
        int pos = 0;
        while (true) {
            int valStart = findValueStart(json, key, pos);
            if (valStart < 0) break;
            if (json.charAt(valStart) != '"') {
                pos = valStart;
                continue;
            }
            int valEnd = findUnescapedQuote(json, valStart + 1);
            if (valEnd < 0) break;
            results.add(unescape(json.substring(valStart + 1, valEnd)));
            pos = valEnd + 1;
        }
        return results;
    }

    /**
     * 文字列 s の指定位置以降で、エスケープされていない最初の '"' を探すメソッド
     * @param s   走査対象の文字列
     * @param pos 走査開始位置
     * @return 見つかった位置。見つからなければ -1
     */
    public static int findUnescapedQuote(String s, int pos) {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == '\\') { pos += 2; continue; }
            if (c == '"') return pos;
            pos++;
        }
        return -1;
    }

    /**
     * 開き括弧に対応する閉じ括弧を、文字列リテラル内を除外して探すメソッド
     * @param s     走査対象の文字列
     * @param start 開き括弧の位置
     * @param open  開き括弧文字
     * @param close 閉じ括弧文字
     * @return 対応する閉じ括弧の位置。見つからなければ -1
     */
    public static int findMatchingBracket(String s, int start, char open, char close) {
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
     * キーとして使われている "key" を探し、その値の開始位置を返すメソッド
     * 直後(空白を挟んでよい)に ':' が続く箇所だけをキーとみなすため、
     * "type":"text" のように値として現れた同じ文字列は無視される
     * @param json 検索対象のJSON
     * @param key  キー名
     * @param from 検索開始位置
     * @return 値の先頭文字の位置。見つからなければ -1
     */
    private static int findValueStart(String json, String key, int from) {
        String quoted = "\"" + key + "\"";
        int idx = json.indexOf(quoted, from);
        while (idx >= 0) {
            int colon = skipWhitespace(json, idx + quoted.length());
            if (colon < json.length() && json.charAt(colon) == ':') {
                int valStart = skipWhitespace(json, colon + 1);
                return valStart < json.length() ? valStart : -1;
            }
            idx = json.indexOf(quoted, idx + 1);
        }
        return -1;
    }

    /**
     * 指定位置から空白文字を読み飛ばすメソッド
     * @param s   走査対象の文字列
     * @param pos 開始位置
     * @return 最初の非空白文字の位置(末尾まで空白なら s.length())
     */
    private static int skipWhitespace(String s, int pos) {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        return pos;
    }
}
