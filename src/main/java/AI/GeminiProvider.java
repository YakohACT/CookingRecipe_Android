package main.java.AI;

import main.java.Recipe.Ingredient;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Google Gemini との通信を担当するクラス。
 * responseSchema による構造化JSON出力を強制することで、
 * 食材リストから外れたレシピや余計な装飾文を返さないようにしている。
 */
public class GeminiProvider extends AbstractRecipeAIProvider {

    @Override
    public String[] generateRecipe(String apiKey, String modelName, String url, ArrayList<Ingredient> allIngredients) throws Exception {
        String cleanKey = apiKey.trim();
        String prompt = buildPrompt(url, allIngredients);
        String safePrompt = jsonEscape(prompt);

        StringBuilder partsJson = new StringBuilder();
        partsJson.append("{\"text\":\"").append(safePrompt).append("\"}");

        // YouTube URL の場合のみ動画自体を入力に添付する。
        // mime_type を明示しないとGeminiがURLを汎用Web取得として扱い、
        // YouTube が返す text/html を「Unsupported MIME type」として弾くため必須。
        // また list= や index= 等の余計なクエリが付いていると INVALID_ARGUMENT になるので、
        // 動画IDだけ抜き出して canonical な watch?v=... URL に正規化する。
        if (url != null && (url.contains("youtube.com") || url.contains("youtu.be"))) {
            String canonical = canonicalYoutubeUrl(url.trim());
            String safeUrl = jsonEscape(canonical);
            partsJson.append(",{\"file_data\":{\"mime_type\":\"video/mp4\",\"file_uri\":\"")
                     .append(safeUrl).append("\"}}");
        }

        // 構造化出力で {title, ingredients[]} のJSONを必ず返させ、温度を下げて安定化する
        String generationConfig = "\"generationConfig\":{"
                + "\"responseMimeType\":\"application/json\","
                + "\"responseSchema\":{"
                +   "\"type\":\"OBJECT\","
                +   "\"properties\":{"
                +     "\"title\":{\"type\":\"STRING\"},"
                +     "\"ingredients\":{\"type\":\"ARRAY\",\"items\":{\"type\":\"STRING\"}}"
                +   "},"
                +   "\"required\":[\"title\",\"ingredients\"]"
                + "},"
                + "\"temperature\":0.3"
                + "}";

        String urlStr = "https://generativelanguage.googleapis.com/v1beta/models/" + modelName
                + ":generateContent?key=" + URLEncoder.encode(cleanKey, StandardCharsets.UTF_8);
        String json = "{\"contents\":[{\"parts\":[" + partsJson + "]}]," + generationConfig + "}";

        String jsonRes = postWithRetry(urlStr, json);

        // candidates[0].content.parts[0].text からモデルが返したJSON文字列を抽出してパース
        return extractAndParse(jsonRes, "text");
    }

    @Override
    public String chat(String apiKey, String modelName, String prompt) throws Exception {
        String cleanKey = apiKey.trim();
        String safePrompt = jsonEscape(prompt);
        // レシピ生成と違い動画添付や固定スキーマは不要。JSON出力だけ促す
        String json = "{\"contents\":[{\"parts\":[{\"text\":\"" + safePrompt + "\"}]}],"
                + "\"generationConfig\":{\"responseMimeType\":\"application/json\",\"temperature\":0.2}}";
        String urlStr = "https://generativelanguage.googleapis.com/v1beta/models/" + modelName
                + ":generateContent?key=" + URLEncoder.encode(cleanKey, StandardCharsets.UTF_8);
        return extractRawContent(postWithRetry(urlStr, json), "text");
    }

    /**
     * 503 (一時的サーバー過負荷) の場合は指数バックオフでリトライする。
     * @param urlStr 送信先URL
     * @param json   POSTするJSONボディ
     * @return レスポンスボディ文字列
     * @throws Exception 通信失敗・リトライ上限到達時
     */
    private String postWithRetry(String urlStr, String json) throws Exception {
        int maxRetries = 3;
        int delayMs = 2000;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                return readStream(conn.getInputStream());
            }
            String errorBody = readStream(conn.getErrorStream());
            if (responseCode == 503 && attempt < maxRetries) {
                Thread.sleep(delayMs);
                delayMs *= 2;
                continue;
            }
            throw new Exception("Gemini API エラー (HTTP " + responseCode + "): " + errorBody);
        }
        throw new Exception("Gemini API エラー: リトライ上限に到達しました");
    }

    /**
     * YouTube URL から動画IDだけ抜き出して canonical な watch?v=... に変換する。
     * playlist や index などの余計なクエリ、Shorts/embed/youtu.be 形式に対応。
     * 解析できない場合は原文をそのまま返す。
     * @param url 任意のYouTubeリンク
     * @return canonical な watch?v=... 形式 (失敗時は原文)
     */
    static String canonicalYoutubeUrl(String url) {
        String videoId = extractYoutubeVideoId(url);
        if (videoId == null) return url;
        return "https://www.youtube.com/watch?v=" + videoId;
    }

    /**
     * YouTubeのURLから動画ID(11文字以上の英数記号)を抽出する。
     * @param url 解析対象のURL
     * @return 動画ID。抽出できなかった場合は null
     */
    private static String extractYoutubeVideoId(String url) {
        // youtu.be/<id>
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "youtu\\.be/([A-Za-z0-9_-]{6,})").matcher(url);
        if (m.find()) return m.group(1);
        // youtube.com/shorts/<id>
        m = java.util.regex.Pattern.compile(
                "youtube\\.com/shorts/([A-Za-z0-9_-]{6,})").matcher(url);
        if (m.find()) return m.group(1);
        // youtube.com/embed/<id>
        m = java.util.regex.Pattern.compile(
                "youtube\\.com/embed/([A-Za-z0-9_-]{6,})").matcher(url);
        if (m.find()) return m.group(1);
        // ?v=<id> or &v=<id>
        m = java.util.regex.Pattern.compile(
                "[?&]v=([A-Za-z0-9_-]{6,})").matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }

    /**
     * /v1beta/models?key=KEY を叩いて利用可能なモデル名を動的取得する。
     * `models/` プレフィックスを除去し、`gemini-` 系のみに絞り込む。
     * 失敗時は静的フォールバック ({@link #getAvailableModels()}) を返す。
     * @param apiKey Gemini APIキー
     * @return 利用可能なモデル一覧
     */
    @Override
    public String[] fetchAvailableModels(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) return getAvailableModels();
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models?key="
                    + URLEncoder.encode(apiKey.trim(), StandardCharsets.UTF_8);
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return getAvailableModels();

            String body = readStream(conn.getInputStream());
            java.util.List<String> names = extractAllJsonStrings(body, "name");
            java.util.List<String> result = new java.util.ArrayList<>();
            for (String n : names) {
                String stripped = n.startsWith("models/") ? n.substring("models/".length()) : n;
                if (!stripped.startsWith("gemini-")) continue; // 埋め込み等を除外
                if (!result.contains(stripped)) result.add(stripped);
            }
            java.util.Collections.sort(result);
            return result.isEmpty() ? getAvailableModels() : result.toArray(new String[0]);
        } catch (Exception e) {
            return getAvailableModels();
        }
    }

    @Override
    public String[] getAvailableModels() {
        // 2026年4月時点のアクティブモデル
        // Gemini 1.x / 2.0 系は全て終了(404エラー)のため除外
        return new String[]{
                "gemini-3.1-pro-preview",  // 最高性能・最新
                "gemini-2.5-pro",          // 高性能・安定版
                "gemini-2.5-flash",        // 高速・高性能バランス
                "gemini-2.5-flash-lite"    // 最速・低コスト
        };
    }
}
