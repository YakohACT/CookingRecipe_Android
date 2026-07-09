package main.java.AI;

import main.java.Recipe.Ingredient;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * OpenAI ChatCompletions API との通信を担当するクラス。
 * response_format=json_object でJSON出力を強制している。
 */
public class OpenAIProvider extends AbstractRecipeAIProvider {

    @Override
    public String[] generateRecipe(String apiKey, String modelName, String url, ArrayList<Ingredient> allIngredients) throws Exception {
        String response = post(apiKey, buildBody(modelName, buildPrompt(url, allIngredients)));
        // choices[0].message.content からモデルが返したJSON文字列を抽出してパース
        return extractAndParse(response, "content");
    }

    @Override
    public String chat(String apiKey, String modelName, String prompt) throws Exception {
        return extractRawContent(post(apiKey, buildBody(modelName, prompt)), "content");
    }

    /**
     * ChatCompletions のリクエストボディを組み立てる。
     * response_format で JSON オブジェクト出力を強制し、温度を下げて安定化する。
     * @param modelName モデル名
     * @param prompt    ユーザープロンプト
     * @return JSONリクエストボディ
     */
    private static String buildBody(String modelName, String prompt) {
        return "{"
                + "\"model\":\"" + modelName + "\","
                + "\"response_format\":{\"type\":\"json_object\"},"
                + "\"temperature\":0.3,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"" + jsonEscape(prompt) + "\"}]"
                + "}";
    }

    /**
     * ChatCompletions エンドポイントへPOSTし、レスポンスボディ全体を返す。
     * @param apiKey OpenAI APIキー
     * @param body   リクエストボディJSON
     * @return レスポンスボディ文字列
     * @throws Exception 通信失敗 / HTTP非200時
     */
    private String post(String apiKey, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("https://api.openai.com/v1/chat/completions").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(60000);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            String errorBody = readStream(conn.getErrorStream());
            throw new Exception("OpenAI API エラー (HTTP " + responseCode + "): " + errorBody);
        }
        return readStream(conn.getInputStream());
    }

    /**
     * /v1/models へGETしてアカウントで利用可能なモデルIDを動的取得する。
     * チャット系(gpt-/o1/o3/o4/chatgpt-)のみフィルタし、音声・埋め込み等の非対象を除外する。
     * 失敗時は静的フォールバック ({@link #getAvailableModels()}) を返す。
     * @param apiKey OpenAI APIキー
     * @return 利用可能なチャットモデル一覧
     */
    @Override
    public String[] fetchAvailableModels(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) return getAvailableModels();
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("https://api.openai.com/v1/models").openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return getAvailableModels();

            String body = readStream(conn.getInputStream());
            java.util.List<String> ids = extractAllJsonStrings(body, "id");
            java.util.List<String> filtered = new java.util.ArrayList<>();
            for (String id : ids) {
                if (!isChatModel(id)) continue;
                filtered.add(id);
            }
            // モデル名で安定ソート
            java.util.Collections.sort(filtered);
            return filtered.isEmpty() ? getAvailableModels() : filtered.toArray(new String[0]);
        } catch (Exception e) {
            return getAvailableModels();
        }
    }

    /**
     * モデルIDがチャット用途かを判定する(/v1/chat/completions に投げて使えるもの)。
     * @param id OpenAIモデルID
     * @return チャット用途として妥当なら true
     */
    private static boolean isChatModel(String id) {
        if (id == null) return false;
        String s = id.toLowerCase();
        // チャット系プレフィックス
        boolean chatPrefix = s.startsWith("gpt-") || s.startsWith("o1") || s.startsWith("o3")
                || s.startsWith("o4") || s.startsWith("chatgpt-");
        if (!chatPrefix) return false;
        // 音声/埋め込み/画像/転写は除外
        return !s.contains("audio") && !s.contains("realtime") && !s.contains("transcribe")
                && !s.contains("tts") && !s.contains("embedding") && !s.contains("whisper")
                && !s.contains("dall-e") && !s.contains("image");
    }

    @Override
    public String[] getAvailableModels() {
        // 2026年4月時点で /v1/chat/completions エンドポイントに対応しているモデル
        return new String[]{
                "gpt-5.5",        // 最高性能フラッグシップ
                "gpt-5.4",        // 高性能・汎用
                "gpt-5.4-mini",   // 高速・低コスト
                "gpt-5.4-nano",   // 最速・最安
                "gpt-4.1",        // 安定版汎用
                "gpt-4.1-mini",   // 安定版・軽量
                "gpt-4.1-nano",   // 安定版・最軽量
                "gpt-4o",         // 旧世代・マルチモーダル
                "gpt-4o-mini"     // 旧世代・軽量
        };
    }
}
