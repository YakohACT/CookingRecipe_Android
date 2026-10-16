package main.java.AI;

import main.java.Util.JsonUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Anthropic Claude API との通信を担当するクラス。
 * プロンプト側でJSON出力を強制している(API側にJSONモードはないため)。
 */
public class ClaudeProvider extends AbstractRecipeAIProvider {

    private static final String API_VERSION = "2023-06-01";

    @Override
    public String chat(String apiKey, String modelName, String prompt) throws Exception {
        // content[0].text にモデルが返したテキストが入っている
        return extractContent(post(apiKey, buildBody(modelName, prompt)), "text");
    }

    /**
     * Messages API のリクエストボディを組み立てる。
     * Claude側にJSONモードはないため、JSON出力はプロンプト側で強制する前提。
     * 表記ゆれ検出では食材数に応じて出力が長くなるため max_tokens は広めに取る
     * @param modelName モデル名
     * @param prompt    ユーザープロンプト
     * @return JSONリクエストボディ
     */
    private static String buildBody(String modelName, String prompt) {
        return "{"
                + "\"model\":\"" + modelName + "\","
                + "\"max_tokens\":2048,"
                + "\"temperature\":0.3,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"" + JsonUtil.escape(prompt) + "\"}]"
                + "}";
    }

    /**
     * Messages エンドポイントへPOSTし、レスポンスボディ全体を返す。
     * @param apiKey Claude APIキー
     * @param body   リクエストボディJSON
     * @return レスポンスボディ文字列
     * @throws Exception 通信失敗 / HTTP非200時
     */
    private String post(String apiKey, String body) throws Exception {
        return readOk(openJsonPost("https://api.anthropic.com/v1/messages", body, 10000, 60000,
                "x-api-key", apiKey, "anthropic-version", API_VERSION), "Claude API エラー", "");
    }

    /**
     * /v1/models へGETしてAnthropicが提供する claude-* モデルを動的取得する。
     * 失敗時は静的フォールバック ({@link #getAvailableModels()}) を返す。
     * @param apiKey Claude APIキー
     * @return claude-* モデル一覧
     */
    @Override
    public String[] fetchAvailableModels(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) return getAvailableModels();
        String body = httpGetOrNull("https://api.anthropic.com/v1/models", 5000, 10000,
                "x-api-key", apiKey, "anthropic-version", API_VERSION);
        if (body == null) return getAvailableModels();

        List<String> result = new ArrayList<>();
        for (String id : JsonUtil.extractAllStrings(body, "id")) {
            if (id.startsWith("claude-") && !result.contains(id)) result.add(id);
        }
        // 新しいモデルが上に来るよう逆順ソート
        result.sort(Comparator.reverseOrder());
        return result.isEmpty() ? getAvailableModels() : result.toArray(new String[0]);
    }

    @Override
    public String[] getAvailableModels() {
        // 2026年4月時点のアクティブモデル (claude-sonnet-4 / claude-opus-4 は2026/4/20退役済み)
        return new String[]{
                "claude-opus-4-7",          // 最高性能・最新フラッグシップ
                "claude-sonnet-4-6",        // 速度と性能のバランス
                "claude-haiku-4-5-20251001" // 最高速・低コスト
        };
    }
}
