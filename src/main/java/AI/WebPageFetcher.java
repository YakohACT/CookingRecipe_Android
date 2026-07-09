package main.java.AI;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 外部URLのHTMLを取得し、AIへ渡すプレーンテキストを抽出するヘルパークラス。
 * Gemini等が直接閲覧できないレシピサイトの
 * 内容をプロンプトに添付するために使う。
 */
public final class WebPageFetcher {

    private static final int MAX_BYTES = 200_000;       // ダウンロード上限 (200KB)
    private static final int MAX_TEXT_LENGTH = 3000;    // 抽出後テキストの上限文字数
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;

    private WebPageFetcher() {}

    /**
     * URLのHTMLを取得し、タグ除去後のプレーンテキストを返す。
     * 失敗時は空文字を返し呼び出し側のフローを止めない。
     * @param url 取得対象のURL
     * @return プレーンテキスト化したページ内容(最大3000文字、失敗時は空文字)
     */
    public static String fetchSummary(String url) {
        if (url == null || url.trim().isEmpty()) return "";
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url.trim()).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; RecipeManager/1.0)");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return "";

            String contentType = conn.getContentType();
            if (contentType != null) {
                String ct = contentType.toLowerCase();
                if (!ct.contains("html") && !ct.contains("xml") && !ct.startsWith("text/")) {
                    return "";
                }
            }

            byte[] bytes = readBytes(conn.getInputStream(), MAX_BYTES);

            // 文字コード判定: Content-Type → HTMLのmetaタグ → UTF-8
            // ASCII領域はISO-8859-1で1対1対応するためmetaタグ検出にはこれを使う
            String preview = new String(bytes, StandardCharsets.ISO_8859_1);
            Charset charset = detectCharset(contentType, preview);

            String html = new String(bytes, charset);
            String text = htmlToText(html);

            if (text.length() > MAX_TEXT_LENGTH) {
                text = text.substring(0, MAX_TEXT_LENGTH) + "…";
            }
            return text;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * InputStream を最大バイト数まで読み込む。
     * @param is       入力ストリーム
     * @param maxBytes 読み込み上限バイト数
     * @return 読み込まれたバイト列
     * @throws Exception 読み込み失敗時
     */
    private static byte[] readBytes(InputStream is, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        while (total < maxBytes) {
            int toRead = Math.min(buf.length, maxBytes - total);
            int n = is.read(buf, 0, toRead);
            if (n <= 0) break;
            out.write(buf, 0, n);
            total += n;
        }
        return out.toByteArray();
    }

    /**
     * Content-Type ヘッダ → HTML の meta タグ → UTF-8 の優先度で文字コードを判定する。
     * @param contentType レスポンスの Content-Type ヘッダ(null可)
     * @param htmlSnippet HTML 本文(null可、meta charset 検出に使う)
     * @return 判定された Charset (失敗時は UTF-8)
     */
    private static Charset detectCharset(String contentType, String htmlSnippet) {
        Pattern charsetPattern = Pattern.compile("charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", Pattern.CASE_INSENSITIVE);
        if (contentType != null) {
            Matcher m = charsetPattern.matcher(contentType);
            if (m.find()) {
                try { return Charset.forName(m.group(1)); } catch (Exception ignore) {}
            }
        }
        if (htmlSnippet != null) {
            Matcher m = Pattern.compile("<meta[^>]+charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", Pattern.CASE_INSENSITIVE)
                    .matcher(htmlSnippet);
            if (m.find()) {
                try { return Charset.forName(m.group(1)); } catch (Exception ignore) {}
            }
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * 簡易HTMLパーサ。script/style/コメント除去 → ブロック要素を改行化 → 残タグ除去
     * → HTMLエンティティ簡易デコード → 空白整形。タイトルを冒頭に配置する。
     * @param html 元のHTML文字列
     * @return プレーンテキスト化された本文
     */
    private static String htmlToText(String html) {
        String title = "";
        Matcher tm = Pattern.compile("<title[^>]*>([\\s\\S]*?)</title>", Pattern.CASE_INSENSITIVE).matcher(html);
        if (tm.find()) {
            title = tm.group(1).replaceAll("\\s+", " ").trim();
        }

        String s = html;
        s = s.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        s = s.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        s = s.replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ");
        s = s.replaceAll("(?is)<!--.*?-->", " ");

        s = s.replaceAll("(?i)<(br|p|div|li|h[1-6]|tr|section|article|dt|dd)[^>]*>", "\n");
        s = s.replaceAll("(?i)</(p|div|li|h[1-6]|tr|section|article|dt|dd)>", "\n");

        s = s.replaceAll("<[^>]+>", " ");

        s = s.replace("&nbsp;", " ")
             .replace("&amp;", "&")
             .replace("&lt;", "<")
             .replace("&gt;", ">")
             .replace("&quot;", "\"")
             .replace("&#39;", "'");

        s = s.replaceAll("[ \\t\\f]+", " ");
        s = s.replaceAll(" *\\n *", "\n");
        s = s.replaceAll("\\n{3,}", "\n\n");
        s = s.trim();

        StringBuilder out = new StringBuilder();
        if (!title.isEmpty()) {
            out.append(title).append("\n\n");
        }
        out.append(s);
        return out.toString();
    }
}
