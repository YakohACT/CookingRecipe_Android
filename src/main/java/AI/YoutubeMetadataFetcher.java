package main.java.AI;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube の動画メタデータ(タイトル + 字幕)をテキスト化するヘルパー。
 * Ollama や OpenAI など動画自体を見られないモデル向けに、
 * AIプロンプトへ「テキスト化された動画コンテンツ」を注入するために使う。
 *
 * 取得方法:
 *  - タイトル: oEmbed API (https://www.youtube.com/oembed) — APIキー不要
 *  - 字幕   : watch ページに埋め込まれた ytInitialPlayerResponse から
 *             captionTracks[].baseUrl を抽出 → そのURLを叩いて timedtext XML を取得
 *
 * 言語優先度: ja > ja-JP > en > その他先頭。何も取れなければ空文字を返す
 */
public final class YoutubeMetadataFetcher {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final int MAX_TRANSCRIPT_LENGTH = 4000;

    private YoutubeMetadataFetcher() {}

    /**
     * 動画タイトルと字幕をひとつのテキストブロックにまとめて返す。
     * 失敗時/取得不能時は空文字。
     * @param url YouTubeの任意形式URL
     * @return 「【動画タイトル】… 【動画字幕(抜粋)】…」形式のテキスト(空文字あり)
     */
    /** 概要欄がこの文字数未満なら情報不足とみなして Whisper 起動を許可する(URLは除外して数える) */
    private static final int DESCRIPTION_SUFFICIENT_THRESHOLD = 150;

    /**
     * 後方互換用。食材名リストを渡せない呼び出しでは食材検出を行わず文字数のみで判定する。
     * @param url YouTube URL
     * @return 整形済みのテキスト(タイトル・概要欄・字幕の連結)
     */
    public static String fetchSummary(String url) {
        return fetchSummary(url, java.util.Collections.emptyList());
    }

    /**
     * YouTube URL から「タイトル+概要欄+字幕」テキストを生成する。
     * 概要欄は URL を除いた実質文字数が閾値以上、または食材名が1つでも含まれていれば
     * 十分とみなし、Whisperによる音声書き起こしを行わない。
     * 字幕も無く概要欄も不十分なときだけ Whisper にフォールバックする。
     * @param url             YouTube URL
     * @param ingredientNames 食材名リスト(概要欄に材料が記載されているか判定するため)
     * @return 整形済みのテキスト
     */
    public static String fetchSummary(String url, java.util.List<String> ingredientNames) {
        String videoId = extractVideoId(url);
        if (videoId == null) return "";

        StringBuilder out = new StringBuilder();

        // 1. タイトル (oEmbed)
        String title = fetchTitle(url);
        if (!title.isEmpty()) {
            out.append("【動画タイトル】\n").append(title).append("\n\n");
        }

        // 2. watch ページを1回だけ取得し、概要欄と captionTracks を同時に抽出
        String watchHtml = "";
        try {
            watchHtml = httpGet("https://www.youtube.com/watch?v=" + videoId, "text/html");
        } catch (Exception ignore) {}

        // 3. 概要欄 (shortDescription)
        String description = extractDescription(watchHtml);
        if (!description.isEmpty()) {
            String shown = description.length() > MAX_TRANSCRIPT_LENGTH
                    ? description.substring(0, MAX_TRANSCRIPT_LENGTH) + "…"
                    : description;
            out.append("【動画概要欄】\n").append(shown).append("\n\n");
        }

        // 4. 公式字幕 (captionTracks → timedtext)
        String transcript = extractTranscript(watchHtml);
        String source = "字幕";

        // 5. 字幕無し かつ 概要欄が情報不足のときだけ Whisper を起動
        //    十分性の判定: URL除外後の文字数が閾値以上 または 食材名が1つ以上含まれる
        String descriptionStripped = stripUrls(description);
        boolean longEnough = descriptionStripped.length() >= DESCRIPTION_SUFFICIENT_THRESHOLD;
        boolean hasIngredient = containsAnyIngredient(descriptionStripped, ingredientNames);
        boolean descriptionSufficient = longEnough || hasIngredient;
        if (transcript.isEmpty() && !descriptionSufficient && WhisperTranscriber.isAvailable()) {
            System.out.println("[YoutubeMetadataFetcher] 字幕無し＋概要欄不十分(URL除外後 "
                    + descriptionStripped.length() + "字, 食材検出="
                    + hasIngredient + ") → Whisperで文字起こしを実行します");
            transcript = WhisperTranscriber.transcribe(url);
            if (!transcript.isEmpty()) source = "Whisper文字起こし";
        }
        if (!transcript.isEmpty()) {
            if (transcript.length() > MAX_TRANSCRIPT_LENGTH) {
                transcript = transcript.substring(0, MAX_TRANSCRIPT_LENGTH) + "…";
            }
            out.append("【動画").append(source).append("(抜粋)】\n").append(transcript);
        }
        return out.toString().trim();
    }

    /**
     * 文字列から http(s):// ではじまる URL を除去し、空白を整える。
     * 概要欄の「実質的な文字数」を測るために使う。
     * @param s 元の文字列
     * @return URL除去後の文字列(null入力時は空文字)
     */
    private static String stripUrls(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replaceAll("https?://\\S+", "")
                .replaceAll("[\\s\\u3000]+", " ")
                .trim();
    }

    /**
     * 文字列に食材名のいずれかが含まれているかを判定する。
     * @param text            検索対象テキスト
     * @param ingredientNames 食材名リスト
     * @return いずれか1つでもマッチすれば true
     */
    private static boolean containsAnyIngredient(String text, java.util.List<String> ingredientNames) {
        if (text == null || text.isEmpty() || ingredientNames == null || ingredientNames.isEmpty()) return false;
        for (String name : ingredientNames) {
            if (name != null && !name.isEmpty() && text.contains(name)) return true;
        }
        return false;
    }

    /**
     * watch ページのHTMLから {@code shortDescription} を抜き出す。
     * 動画の概要欄(投稿者が書いた説明文)に相当する。
     * @param html watch ページのHTML
     * @return 概要欄テキスト(失敗/不在時は空文字)
     */
    private static String extractDescription(String html) {
        if (html == null || html.isEmpty()) return "";
        // ytInitialPlayerResponse 内の "shortDescription":"..." を探す
        int idx = html.indexOf("\"shortDescription\":\"");
        if (idx < 0) return "";
        int valStart = idx + "\"shortDescription\":\"".length();
        int valEnd = findUnescapedQuote(html, valStart);
        if (valEnd < 0 || valEnd <= valStart) return "";
        return jsonUnescape(html.substring(valStart, valEnd)).trim();
    }

    /**
     * watch ページのHTMLから captionTracks を抽出し、適切な言語の timedtext XML を
     * ダウンロードしてテキスト化する。
     * @param html watch ページのHTML
     * @return 字幕テキスト(失敗/字幕なしの場合は空文字)
     */
    private static String extractTranscript(String html) {
        if (html == null || html.isEmpty()) return "";
        try {
            int idx = html.indexOf("\"captionTracks\":");
            if (idx == -1) return "";

            String tail = html.substring(idx);
            Matcher m = Pattern.compile(
                    "\"baseUrl\"\\s*:\\s*\"([^\"]+)\"[^}]*\"languageCode\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(tail);

            Map<String, String> langToUrl = new LinkedHashMap<>();
            while (m.find()) {
                String baseUrl = m.group(1).replace("\\u0026", "&").replace("\\/", "/");
                String lang = m.group(2);
                langToUrl.putIfAbsent(lang, baseUrl);
            }
            if (langToUrl.isEmpty()) return "";

            String chosen = null;
            for (String lang : new String[]{"ja", "ja-JP", "en", "en-US", "en-GB"}) {
                if (langToUrl.containsKey(lang)) {
                    chosen = langToUrl.get(lang);
                    break;
                }
            }
            if (chosen == null) chosen = langToUrl.values().iterator().next();

            String xml = httpGet(chosen, "text/xml");
            return parseTranscriptXml(xml);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * YouTubeのURLから動画IDを抽出する。
     * @param url 解析対象のURL
     * @return 動画ID。抽出できなければ null
     */
    private static String extractVideoId(String url) {
        if (url == null) return null;
        Matcher m;
        m = Pattern.compile("youtu\\.be/([A-Za-z0-9_-]{6,})").matcher(url);
        if (m.find()) return m.group(1);
        m = Pattern.compile("youtube\\.com/shorts/([A-Za-z0-9_-]{6,})").matcher(url);
        if (m.find()) return m.group(1);
        m = Pattern.compile("youtube\\.com/embed/([A-Za-z0-9_-]{6,})").matcher(url);
        if (m.find()) return m.group(1);
        m = Pattern.compile("[?&]v=([A-Za-z0-9_-]{6,})").matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }

    /**
     * oEmbed APIで動画タイトルを取得する。
     * @param videoUrl YouTube動画のURL
     * @return 動画タイトル(失敗時は空文字)
     */
    private static String fetchTitle(String videoUrl) {
        try {
            String oembedUrl = "https://www.youtube.com/oembed?format=json&url="
                    + URLEncoder.encode(videoUrl, StandardCharsets.UTF_8);
            String json = httpGet(oembedUrl, "application/json");
            if (json.isEmpty()) return "";
            int keyIdx = json.indexOf("\"title\"");
            if (keyIdx == -1) return "";
            int colonIdx = json.indexOf(":", keyIdx);
            int valStart = json.indexOf("\"", colonIdx) + 1;
            int valEnd = findUnescapedQuote(json, valStart);
            if (valEnd <= valStart) return "";
            return jsonUnescape(json.substring(valStart, valEnd));
        } catch (Exception e) {
            return "";
        }
    }


    /**
     * timedtext の {@code <text>...</text>} を改行区切りで連結してプレーンテキスト化する。
     * @param xml timedtext XML
     * @return 連結された字幕テキスト(空入力時は空文字)
     */
    private static String parseTranscriptXml(String xml) {
        if (xml == null || xml.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("<text[^>]*>([\\s\\S]*?)</text>").matcher(xml);
        while (m.find()) {
            String chunk = m.group(1)
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .replace("&nbsp;", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (!chunk.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(chunk);
            }
        }
        return sb.toString();
    }

    /**
     * 指定 URL に GET リクエストを送り、レスポンスボディを UTF-8 で読み込む。
     * @param url    取得対象のURL
     * @param accept Accept ヘッダ値
     * @return レスポンスボディ文字列(2xx以外は空文字)
     * @throws Exception 通信失敗時
     */
    private static String httpGet(String url, String accept) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; RecipeManager/1.0)");
        conn.setRequestProperty("Accept", accept);
        conn.setRequestProperty("Accept-Language", "ja,en;q=0.9");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) return "";
        try (InputStream is = conn.getInputStream();
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            return sb.toString();
        }
    }

    /**
     * pos 以降でエスケープされていない最初の '"' を探す。
     * @param s   検索対象
     * @param pos 開始位置
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
     * バックスラッシュ系のJSONエスケープ(quote, slash, n, t, ユニコード4桁等)を実体に戻す。
     * @param s エスケープされたJSON文字列値
     * @return 復元された文字列
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
                    case 'u':
                        if (i + 5 < s.length()) {
                            try {
                                int code = Integer.parseInt(s.substring(i + 2, i + 6), 16);
                                sb.append((char) code);
                                i += 5;
                            } catch (NumberFormatException e) { sb.append(c); }
                        } else { sb.append(c); }
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
