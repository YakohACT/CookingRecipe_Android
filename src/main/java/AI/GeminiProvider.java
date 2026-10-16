package main.java.AI;

import main.java.Util.JsonUtil;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Google Gemini との通信を担当するクラス。
 * responseSchema による構造化JSON出力を強制することで、
 * 食材リストから外れたレシピや余計な装飾文を返さないようにしている。
 */
public class GeminiProvider extends AbstractRecipeAIProvider {

    private static final String API_BASE = "https://generativelanguage.googleapis.com/v1beta/models";

    @Override
    public RecipeSuggestion generateRecipe(String apiKey, String modelName, String url,
                                           List<String> ingredientNames, List<String> categoryNames) throws Exception {
        String prompt = buildPrompt(url, ingredientNames, categoryNames);
        String safePrompt = JsonUtil.escape(prompt);

        StringBuilder partsJson = new StringBuilder();
        partsJson.append("{\"text\":\"").append(safePrompt).append("\"}");

        // YouTube URL の場合のみ動画自体を入力に添付する。
        // mime_type を明示しないとGeminiがURLを汎用Web取得として扱い、
        // YouTube が返す text/html を「Unsupported MIME type」として弾くため必須。
        // また list= や index= 等の余計なクエリが付いていると INVALID_ARGUMENT になるので、
        // 動画IDだけ抜き出して canonical な watch?v=... URL に正規化する。
        if (YoutubeMetadataFetcher.isYoutubeUrl(url)) {
            String canonical = canonicalYoutubeUrl(url.trim());
            String safeUrl = JsonUtil.escape(canonical);
            partsJson.append(",{\"file_data\":{\"mime_type\":\"video/mp4\",\"file_uri\":\"")
                     .append(safeUrl).append("\"}}");
        }

        // 構造化出力で {title, ingredients[{name, amount, unit}], categories[]} のJSONを必ず返させ、温度を下げて安定化する
        String generationConfig = "\"generationConfig\":{"
                + "\"responseMimeType\":\"application/json\","
                + "\"responseSchema\":{"
                +   "\"type\":\"OBJECT\","
                +   "\"properties\":{"
                +     "\"title\":{\"type\":\"STRING\"},"
                +     "\"ingredients\":{\"type\":\"ARRAY\",\"items\":{"
                +       "\"type\":\"OBJECT\","
                +       "\"properties\":{"
                +         "\"name\":{\"type\":\"STRING\"},"
                +         "\"amount\":{\"type\":\"STRING\"},"
                +         "\"unit\":{\"type\":\"STRING\"}"
                +       "},"
                +       "\"required\":[\"name\",\"amount\",\"unit\"]"
                +     "}},"
                +     "\"categories\":{\"type\":\"ARRAY\",\"items\":{\"type\":\"STRING\"}}"
                +   "},"
                +   "\"required\":[\"title\",\"ingredients\",\"categories\"]"
                + "},"
                + "\"temperature\":0.3"
                + "}";

        String json = "{\"contents\":[{\"parts\":[" + partsJson + "]}]," + generationConfig + "}";

        String jsonRes = postWithRetry(generateContentUrl(apiKey, modelName), json);

        // candidates[0].content.parts[0].text からモデルが返したJSON文字列を抽出してパース
        return parseRecipeJson(extractContent(jsonRes, "text"));
    }

    @Override
    public String chat(String apiKey, String modelName, String prompt) throws Exception {
        String safePrompt = JsonUtil.escape(prompt);
        // レシピ生成と違い動画添付や固定スキーマは不要。JSON出力だけ促す
        String json = "{\"contents\":[{\"parts\":[{\"text\":\"" + safePrompt + "\"}]}],"
                + "\"generationConfig\":{\"responseMimeType\":\"application/json\",\"temperature\":0.2}}";
        return extractContent(postWithRetry(generateContentUrl(apiKey, modelName), json), "text");
    }

    /**
     * generateContent エンドポイントのURLを組み立てる(APIキーはクエリで渡す)。
     * @param apiKey    Gemini APIキー
     * @param modelName モデル名
     * @return リクエスト先URL
     */
    private static String generateContentUrl(String apiKey, String modelName) {
        return API_BASE + "/" + modelName
                + ":generateContent?key=" + urlEncode(apiKey.trim());
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
            HttpURLConnection conn = openJsonPost(urlStr, json, 10000, 60000);
            if (conn.getResponseCode() == 503 && attempt < maxRetries) {
                Thread.sleep(delayMs);
                delayMs *= 2;
                continue;
            }
            return readOk(conn, "Gemini API エラー", "");
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
        String videoId = YoutubeMetadataFetcher.extractVideoId(url);
        if (videoId == null) return url;
        return "https://www.youtube.com/watch?v=" + videoId;
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
        String body = httpGetOrNull(API_BASE + "?key=" + urlEncode(apiKey.trim()),
                5000, 10000);
        if (body == null) return getAvailableModels();

        List<String> result = new ArrayList<>();
        for (String n : JsonUtil.extractAllStrings(body, "name")) {
            String stripped = n.startsWith("models/") ? n.substring("models/".length()) : n;
            if (!stripped.startsWith("gemini-")) continue; // 埋め込み等を除外
            if (!result.contains(stripped)) result.add(stripped);
        }
        Collections.sort(result);
        return result.isEmpty() ? getAvailableModels() : result.toArray(new String[0]);
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
