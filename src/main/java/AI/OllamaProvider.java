package main.java.AI;

import main.java.Util.JsonUtil;

import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ローカルLLM Ollama (/api/chat) との通信を担当するクラス。
 * モデルファミリ(Llama / Gemma / Mistral / Phi / Qwen など)によるAPIの違いは無いため、
 * どのモデルもこのクラスで扱う。
 *
 * Android 版では端末上に Ollama が無いことが多いため、接続先URLをAI設定で変更できる
 * (例: 同じLANのPC http://192.168.1.10:11434 / エミュレータからホストPC http://10.0.2.2:11434)。
 * 未設定なら http://localhost:11434 (Termux 等で端末内に起動した Ollama) に接続する。
 *
 * Ollama 共通の挙動:
 * - format: "json" を指定して必ずJSONで返させる
 * - temperature: 0.3 で安定化
 * - apiKey は不要(渡されても無視)
 */
public class OllamaProvider extends AbstractRecipeAIProvider {

    /** Ollama サーバーのデフォルトURL */
    static final String DEFAULT_URL = "http://localhost:11434";

    /** 接続先のベースURL(末尾の "/" や "/api" は除去済み) */
    private final String baseUrl;

    /**
     * @param baseUrl Ollama サーバーのURL(例: http://192.168.1.10:11434)。空文字なら {@link #DEFAULT_URL}
     */
    public OllamaProvider(String baseUrl) {
        String u = LlamaCppProvider.normalizeUrl(baseUrl);
        if (u.endsWith("/api")) u = u.substring(0, u.length() - "/api".length());
        this.baseUrl = u.isEmpty() ? DEFAULT_URL : u;
    }

    /**
     * Ollama に `GET /api/tags` を送って実際にpull済みのモデル名を取得する。
     * Ollamaが起動していない/失敗時は静的リスト({@link #getAvailableModels()})にフォールバック。
     * @param apiKey 未使用(API互換のため受け取るが無視する)
     * @return Ollamaに導入済みのモデル名配列
     */
    @Override
    public String[] fetchAvailableModels(String apiKey) {
        String body = httpGetOrNull(baseUrl + "/api/tags", 2000, 5000);
        if (body == null) return getAvailableModels();

        // {"models":[{"name":"llama3:8b","modified_at":"...",...}, ...]}
        List<String> uniq = new ArrayList<>();
        for (String n : JsonUtil.extractAllStrings(body, "name")) {
            if (n != null && !n.isEmpty() && !uniq.contains(n)) uniq.add(n);
        }
        Collections.sort(uniq);
        return uniq.isEmpty() ? getAvailableModels() : uniq.toArray(new String[0]);
    }

    @Override
    public String chat(String apiKey, String modelName, String prompt) throws Exception {
        // Ollama レスポンス形式: {"message": {"role":"assistant","content":"..."}, "done": true, ...}
        return extractContent(post(modelName, prompt), "content");
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
                + "\"model\":\"" + JsonUtil.escape(modelName) + "\","
                + "\"stream\":false,"
                + "\"format\":\"json\","
                + "\"options\":{\"temperature\":0.3},"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"" + JsonUtil.escape(prompt) + "\"}]"
                + "}";

        HttpURLConnection conn;
        try {
            // ローカルLLMは生成が遅いため読み取りタイムアウトは長めに
            conn = openJsonPost(baseUrl + "/api/chat", json, 5000, 120000);
        } catch (ConnectException e) {
            throw new Exception("Ollama に接続できません。`ollama serve` が " + baseUrl
                    + " で起動しているか、AI設定のサーバーURLを確認してください。", e);
        }
        return readOk(conn, "Ollama API エラー",
                "\n(モデル '" + modelName + "' が `ollama pull` 済みかも確認してください)");
    }

    /**
     * Ollama から導入済みモデルを取得できないときに表示する、動作確認済みモデルの一覧を返す。
     * @return 動作確認済みモデル名の配列
     */
    @Override
    public String[] getAvailableModels() {
        return new String[]{
                "llama3:8b",
                "gemma4:e2b",
                "gemma4:e4b",
                "mistral",
                "mistral:7b",
                "mistral-nemo",
                "mistral-small",
                "mixtral",
                "phi4",
                "phi3.5",
                "phi3",
                "phi3:mini",
                "phi3:medium",
                "qwen3.5:4b",
                "qwen3.5:9b",
                "qwen3-vl:4b",
                "qwen3-vl:8b"
        };
    }
}
