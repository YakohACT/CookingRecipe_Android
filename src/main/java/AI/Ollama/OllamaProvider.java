package main.java.AI.Ollama;

import main.java.AI.AbstractRecipeAIProvider;
import main.java.Recipe.Ingredient;

import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * ローカルLLM Ollama との通信を担う抽象基底クラス。
 * 各モデルファミリ(Llama / Gemma / Mistral / Phi / Qwen) は本クラスを継承し、
 * getAvailableModels() で対応モデル名を返す。
 *
 * Ollama 共通の挙動:
 * - format: "json" を指定して必ずJSONで返させる
 * - temperature: 0.3 で安定化
 * - apiKey は不要(渡されても無視)
 *
 * Android では Ollama がスマホ内の localhost で動いていることは通常無いため、
 * 接続先ベースURLを {@link #setBaseUrl(String)} で変更できる
 * (例: 同一LAN上のPCで `ollama serve` している場合は http://192.168.x.x:11434)。
 */
public abstract class OllamaProvider extends AbstractRecipeAIProvider {

    /** Ollama サーバーのデフォルトベースURL */
    public static final String DEFAULT_BASE_URL = "http://localhost:11434";

    /** 現在の接続先ベースURL(AI設定画面から変更可能) */
    private static volatile String baseUrl = DEFAULT_BASE_URL;

    /**
     * Ollama サーバーの接続先ベースURLを設定する。
     * @param url 例 "http://192.168.1.10:11434"。null/空文字ならデフォルトに戻す
     */
    public static void setBaseUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            baseUrl = DEFAULT_BASE_URL;
        } else {
            baseUrl = url.trim().replaceAll("/+$", "");
        }
    }

    /**
     * @return 現在の接続先ベースURL
     */
    public static String getBaseUrl() {
        return baseUrl;
    }

    /**
     * ローカルOllamaに `GET /api/tags` を送って実際にpull済みのモデル名を取得する。
     * Ollamaが起動していない/失敗時はサブクラスの静的リスト({@link #getAvailableModels()})にフォールバック。
     * @param apiKey 未使用(API互換のため受け取るが無視する)
     * @return Ollamaに導入済みのモデル名配列
     */
    @Override
    public String[] fetchAvailableModels(String apiKey) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/api/tags").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(5000);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return getAvailableModels();
            String body = readStream(conn.getInputStream());
            // {"models":[{"name":"llama3:8b","modified_at":"...",...}, ...]}
            java.util.List<String> names = extractAllJsonStrings(body, "name");
            java.util.List<String> uniq = new java.util.ArrayList<>();
            for (String n : names) {
                if (n != null && !n.isEmpty() && !uniq.contains(n)) uniq.add(n);
            }
            java.util.Collections.sort(uniq);
            return uniq.isEmpty() ? getAvailableModels() : uniq.toArray(new String[0]);
        } catch (Exception e) {
            return getAvailableModels();
        }
    }

    @Override
    public String[] generateRecipe(String apiKey, String modelName, String url, ArrayList<Ingredient> allIngredients) throws Exception {
        String response = post(modelName, buildPrompt(url, allIngredients));
        // Ollama レスポンス形式: {"message": {"role":"assistant","content":"..."}, "done": true, ...}
        // content フィールドの中身がモデルの返したJSON文字列
        return extractAndParse(response, "content");
    }

    @Override
    public String chat(String apiKey, String modelName, String prompt) throws Exception {
        return extractRawContent(post(modelName, prompt), "content");
    }

    /**
     * Ollama /api/chat へPOSTし、レスポンスボディ全体を返す。
     * format:"json" で必ずJSONを返させ、温度を下げて安定化する。
     * @param modelName モデル名
     * @param prompt    ユーザープロンプト
     * @return レスポンスボディ文字列
     * @throws Exception 接続不可 / HTTP非200時
     */
    private String post(String modelName, String prompt) throws Exception {
        String json = "{"
                + "\"model\":\"" + modelName + "\","
                + "\"stream\":false,"
                + "\"format\":\"json\","
                + "\"options\":{\"temperature\":0.3},"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"" + jsonEscape(prompt) + "\"}]"
                + "}";

        String chatUrl = baseUrl + "/api/chat";
        HttpURLConnection conn;
        try {
            conn = (HttpURLConnection) new URL(chatUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(120000); // ローカルLLMは生成が遅いため長めに
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
        } catch (ConnectException e) {
            throw new Exception("Ollama に接続できません。`ollama serve` が " + chatUrl
                    + " で起動しているか、AI設定のOllamaサーバーURLが正しいか確認してください。", e);
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            String errorBody = readStream(conn.getErrorStream());
            throw new Exception("Ollama API エラー (HTTP " + responseCode + "): " + errorBody
                    + "\n(モデル '" + modelName + "' が `ollama pull` 済みかも確認してください)");
        }
        return readStream(conn.getInputStream());
    }
}
