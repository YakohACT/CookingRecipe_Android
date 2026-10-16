package main.java.AI;

import main.java.Util.JsonUtil;

import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

/**
 * ローカルLLM llama.cpp の llama-server との通信を担当するクラス。
 * llama-server が提供する OpenAI 互換API(/v1/chat/completions)を使う。
 *
 * llama.cpp 共通の挙動:
 * - 接続先URLはAI設定で変更できる
 * - response_format: json_object を指定して必ずJSONで返させる
 * - temperature: 0.3 で安定化
 * - 推論(thinking)対応モデルでも思考を無効化して応答を速くする
 * - apiKey は不要
 * - model は /v1/models で取得したものを指定する(ルーターモードでは必須、1モデル起動時は起動したモデル)
 */
public class LlamaCppProvider extends AbstractRecipeAIProvider {

    /** 接続先のベースURL(末尾の "/" や "/v1" は除去済み。未設定なら空文字) */
    private final String baseUrl;

    /**
     * @param baseUrl llama-server のURL(例: http://localhost:9931)。未設定なら空文字
     */
    public LlamaCppProvider(String baseUrl) {
        this.baseUrl = normalizeUrl(baseUrl);
    }

    /**
     * 入力されたURLを "http://host:port" の形に整える。
     * スキーム省略(localhost:9931)や、OpenAI互換のベースURLとしてよく使われる "/v1" 付きにも対応する。
     * @param url 入力URL(null可)
     * @return 正規化したベースURL(空入力なら空文字)
     */
    static String normalizeUrl(String url) {
        String u = (url == null) ? "" : url.trim();
        if (u.isEmpty()) return "";
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/v1")) u = u.substring(0, u.length() - "/v1".length());
        return u;
    }

    /**
     * ローカルの llama-server に `GET /v1/models` を送って読み込み済みのモデル名を取得する。
     * URL未設定 / llama-server が起動していない / 失敗時は {@link #getAvailableModels()} にフォールバック。
     * @param apiKey 未使用(API互換のため受け取るが無視する)
     * @return llama-server が読み込んでいるモデル名の配列
     */
    @Override
    public String[] fetchAvailableModels(String apiKey) {
        if (baseUrl.isEmpty()) return getAvailableModels();
        String body = httpGetOrNull(baseUrl + "/v1/models", 2000, 5000);
        if (body == null) return getAvailableModels();

        // {"object":"list","data":[{"id":"model.gguf","object":"model",...}, ...]}
        List<String> uniq = new ArrayList<>();
        for (String id : JsonUtil.extractAllStrings(body, "id")) {
            if (!id.isEmpty() && !uniq.contains(id)) uniq.add(id);
        }
        return uniq.isEmpty() ? getAvailableModels() : uniq.toArray(new String[0]);
    }

    @Override
    public String chat(String apiKey, String modelName, String prompt) throws Exception {
        // choices[0].message.content にモデルが返したテキストが入っている
        return extractContent(post(buildBody(modelName, prompt)), "content");
    }

    /**
     * ChatCompletions 形式のリクエストボディを組み立てる。
     * chat_template_kwargs の enable_thinking は Qwen3 など推論対応モデルの思考を止めるためのもので、
     * 対応していないチャットテンプレートでは無視される(OpenAI本家は未知のパラメータを拒否するため llama.cpp 専用)
     * @param modelName モデル名(パスやエイリアスの場合もあるためエスケープする)
     * @param prompt    ユーザープロンプト
     * @return JSONリクエストボディ
     */
    private static String buildBody(String modelName, String prompt) {
        return "{"
                + "\"model\":\"" + JsonUtil.escape(modelName) + "\","
                + "\"response_format\":{\"type\":\"json_object\"},"
                + "\"temperature\":0.3,"
                + "\"chat_template_kwargs\":{\"enable_thinking\":false},"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"" + JsonUtil.escape(prompt) + "\"}]"
                + "}";
    }

    /**
     * llama-server の /v1/chat/completions へPOSTし、レスポンスボディ全体を返す。
     * @param body リクエストボディJSON
     * @return レスポンスボディ文字列
     * @throws Exception URL未設定 / 接続不可 / HTTP非200時
     */
    private String post(String body) throws Exception {
        if (baseUrl.isEmpty()) {
            throw new Exception("llama.cpp のサーバーURLが設定されていません。AI設定で llama-server のURLを入力してください。");
        }
        HttpURLConnection conn;
        try {
            // ローカルLLMは生成が遅く、ルーターモードでは初回リクエスト時にモデルの読み込みも行うため読み取りタイムアウトは長めに
            conn = openJsonPost(baseUrl + "/v1/chat/completions", body, 5000, 300000);
        } catch (ConnectException e) {
            throw new Exception("llama.cpp に接続できません。`llama-server` が " + baseUrl
                    + " で起動しているか、AI設定のサーバーURLを確認してください。", e);
        }
        return readOk(conn, "llama.cpp API エラー",
                "\n(コンテキスト長不足の場合は llama-server を -c 8192 などで起動してください)");
    }

    /**
     * llama-server は起動時に読み込んだモデルを使うため、固定のモデル一覧は持たない。
     * 起動中であれば {@link #fetchAvailableModels(String)} で実際のモデル名を取得できる。
     * @return 空配列
     */
    @Override
    public String[] getAvailableModels() {
        return new String[0];
    }
}
