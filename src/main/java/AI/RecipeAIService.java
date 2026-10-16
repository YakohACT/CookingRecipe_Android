package main.java.AI;

import java.util.List;

/**
 * 設定済みのAIプロバイダーへ処理を委譲する窓口クラス。
 * 入出力は文字列(URL・食材名・プロンプト)だけで、レシピのドメインクラスには依存しない。
 * 設定は生成時に固定され、変更する場合は {@link AiSettings#createService()} で作り直す。
 */
public class RecipeAIService {

    public enum Provider {
        OPENAI, GEMINI, CLAUDE, OLLAMA, LLAMA_CPP;

        /**
         * このプロバイダーの利用にAPIキーが必要かを返す。
         * @return APIキーが必要なら true(ローカルで動く Ollama / llama.cpp は不要)
         */
        public boolean requiresApiKey() {
            return !isLocal();
        }

        /**
         * 接続先サーバーURLを設定して使うローカルLLMかを返す。
         * @return Ollama / llama.cpp なら true
         */
        public boolean isLocal() {
            return this == OLLAMA || this == LLAMA_CPP;
        }
    }

    private final Provider provider;
    private final String apiKey;
    private final String modelName;
    private final String serverUrl;

    /**
     * @param provider  AIプロバイダー
     * @param apiKey    APIキー(ローカルLLMは空文字でよい)
     * @param modelName 利用モデル名
     * @param serverUrl ローカルLLM(Ollama / llama-server)のURL(例: http://192.168.1.10:11434)。それ以外は空文字でよい
     */
    public RecipeAIService(Provider provider, String apiKey, String modelName, String serverUrl) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.serverUrl = serverUrl;
    }

    /**
     * プロバイダーの呼び出しに必要な設定が揃っているかを判定する。
     * APIキーが必要なプロバイダーはキー、llama.cpp はサーバーURLが必要
     * (Ollama はURL未設定なら http://localhost:11434 に接続する)。
     * @param provider  AIプロバイダー
     * @param apiKey    APIキー
     * @param serverUrl ローカルLLMのURL
     * @return 呼び出し可能なら true
     */
    public static boolean isConfigured(Provider provider, String apiKey, String serverUrl) {
        if (provider == null) return false;
        if (provider.requiresApiKey() && (apiKey == null || apiKey.isEmpty())) return false;
        return provider != Provider.LLAMA_CPP || (serverUrl != null && !serverUrl.trim().isEmpty());
    }

    /**
     * 設定済みプロバイダーへレシピ提案(料理名・分量付きの食材・カテゴリー)を依頼する。
     * APIキー(llama.cpp はサーバーURL)が未設定の場合は例外を投げる。
     * @param url             参照URL
     * @param ingredientNames 利用可能な食材名のリスト
     * @param categoryNames   選べるカテゴリーのラベル一覧
     * @return AIが提案したレシピ
     * @throws Exception API呼び出し失敗時 / 未設定時
     */
    public RecipeSuggestion suggestRecipe(String url, List<String> ingredientNames, List<String> categoryNames) throws Exception {
        ensureConfigured();
        return createProvider(provider, serverUrl).generateRecipe(apiKey, modelName, url, ingredientNames, categoryNames);
    }

    /**
     * 設定済みプロバイダーに任意のプロンプトを送り、モデルが返した本文を返す。
     * APIキー(llama.cpp はサーバーURL)が未設定の場合は例外を投げる。
     * @param prompt 送信するプロンプト
     * @return モデルが返したテキスト(多くの場合JSON文字列)
     * @throws Exception API呼び出し失敗時 / 未設定時
     */
    public String chat(String prompt) throws Exception {
        ensureConfigured();
        return createProvider(provider, serverUrl).chat(apiKey, modelName, prompt);
    }

    /**
     * 指定されたプロバイダーの静的なモデル一覧(フォールバック用)を取得する。
     * @param provider 対象のAIプロバイダー
     * @return モデル名の配列
     */
    public static String[] defaultModels(Provider provider) {
        return createProvider(provider, "").getAvailableModels();
    }

    /**
     * 動的にプロバイダのAPIへ問い合わせて利用可能なモデル一覧を取得する。
     * Ollama は設定したURLの /api/tags、llama.cpp は設定したURLの /v1/models、それ以外はプロバイダの models エンドポイントを叩く。
     * 失敗時は各プロバイダの静的フォールバックが返る(例外は呼び出し側へ漏れない)。
     * 通信するためメインスレッド以外から呼ぶこと。
     * @param provider  対象プロバイダー
     * @param apiKey    APIキー(ローカルLLMは未使用、null/空文字許容)
     * @param serverUrl ローカルLLMのURL(ローカルLLM以外は未使用)
     * @return モデル名配列
     */
    public static String[] fetchModels(Provider provider, String apiKey, String serverUrl) {
        return createProvider(provider, serverUrl).fetchAvailableModels(apiKey);
    }

    /**
     * 呼び出しに必要な設定が揃っていなければ例外を投げる。
     * @throws Exception APIキー / サーバーURL 未設定時
     */
    private void ensureConfigured() throws Exception {
        if (isConfigured(provider, apiKey, serverUrl)) return;
        if (provider == Provider.LLAMA_CPP) {
            throw new Exception("llama.cpp のサーバーURLが設定されていない");
        }
        throw new Exception("APIキーが設定されていない");
    }

    /**
     * プロバイダーに対応する具象クラスを生成する。
     * @param provider  AIプロバイダー
     * @param serverUrl ローカルLLMのURL(Ollama / llama.cpp のときだけ使う)
     * @return 対応する具象 AbstractRecipeAIProvider
     */
    private static AbstractRecipeAIProvider createProvider(Provider provider, String serverUrl) {
        switch (provider) {
            case GEMINI: return new GeminiProvider();
            case CLAUDE: return new ClaudeProvider();
            case OLLAMA: return new OllamaProvider(serverUrl);
            case LLAMA_CPP: return new LlamaCppProvider(serverUrl);
            case OPENAI: default: return new OpenAIProvider();
        }
    }
}
