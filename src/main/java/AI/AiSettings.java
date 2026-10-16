package main.java.AI;

import android.content.SharedPreferences;

/**
 * AI利用設定(プロバイダー・APIキー・モデル・ローカルLLMのURL)を SharedPreferences に保存/復元するクラス。
 * APIキーは {@link SecureKeyStore} で暗号化してから保存する。
 * 設定済みの内容で {@link RecipeAIService} を生成する役割も持つ。
 * (デスクトップ版の java.util.prefs.Preferences を SharedPreferences に置き換えたもの)
 */
public class AiSettings {

    private static final String PREF_PROVIDER = "aiProvider";
    private static final String PREF_MODEL_PREFIX = "aiModel.";
    private static final String PREF_API_KEY_PREFIX = "apiKey.";
    private static final String PREF_LLAMA_CPP_URL = "llamaCppUrl";
    private static final String PREF_OLLAMA_URL = "ollamaUrl";

    private final SharedPreferences prefs;
    private final SecureKeyStore secure;

    private RecipeAIService.Provider provider = RecipeAIService.Provider.OPENAI;

    /**
     * 保存先を受け取り、前回選択していたプロバイダーを復元する。
     * @param prefs 設定の保存先
     */
    public AiSettings(SharedPreferences prefs) {
        this.prefs = prefs;
        this.secure = new SecureKeyStore();
        String savedProvider = prefs.getString(PREF_PROVIDER, "");
        if (savedProvider != null && !savedProvider.isEmpty()) {
            try {
                provider = RecipeAIService.Provider.valueOf(savedProvider);
            } catch (IllegalArgumentException ignore) {}
        }
    }

    /**
     * @return 現在選択中のAIプロバイダー
     */
    public RecipeAIService.Provider getProvider() { return provider; }

    /**
     * 現在のAIプロバイダーを切替えて保存する。
     * @param p 切替先のプロバイダー
     */
    public void setProvider(RecipeAIService.Provider p) {
        this.provider = p;
        if (p != null) prefs.edit().putString(PREF_PROVIDER, p.name()).apply();
    }

    /**
     * 指定プロバイダーのAPIキーを復号して返す。
     * @param p 対象プロバイダー
     * @return 復号済みのAPIキー(未設定/復号失敗時は空文字)
     */
    public String getApiKey(RecipeAIService.Provider p) {
        if (p == null) return "";
        String stored = prefs.getString(PREF_API_KEY_PREFIX + p.name(), "");
        if (stored == null || stored.isEmpty()) return "";
        return secure.decrypt(stored);
    }

    /**
     * 指定プロバイダーのAPIキーを暗号化して保存する。空文字なら削除する。
     * @param p        対象プロバイダー
     * @param plainKey 平文のAPIキー(空文字で削除)
     */
    public void setApiKey(RecipeAIService.Provider p, String plainKey) {
        if (p == null) return;
        if (plainKey == null || plainKey.isEmpty()) {
            prefs.edit().remove(PREF_API_KEY_PREFIX + p.name()).apply();
            return;
        }
        prefs.edit().putString(PREF_API_KEY_PREFIX + p.name(), secure.encrypt(plainKey)).apply();
    }

    /**
     * 指定プロバイダーで保存済みのモデル名を返す。
     * @param p 対象プロバイダー
     * @return 保存値があればそれ、無ければそのプロバイダーの先頭モデル
     */
    public String getModel(RecipeAIService.Provider p) {
        if (p == null) return "";
        String saved = prefs.getString(PREF_MODEL_PREFIX + p.name(), "");
        if (saved != null && !saved.isEmpty()) return saved;
        // 未設定のときはそのプロバイダーの最初の利用可能モデルにフォールバック
        String[] models = RecipeAIService.defaultModels(p);
        return models.length > 0 ? models[0] : "";
    }

    /**
     * 指定プロバイダーの利用モデル名を保存する。空文字なら削除する。
     * @param p     対象プロバイダー
     * @param model 保存するモデル名(空文字で削除)
     */
    public void setModel(RecipeAIService.Provider p, String model) {
        if (p == null) return;
        if (model == null || model.isEmpty()) {
            prefs.edit().remove(PREF_MODEL_PREFIX + p.name()).apply();
        } else {
            prefs.edit().putString(PREF_MODEL_PREFIX + p.name(), model).apply();
        }
    }

    /**
     * ローカルLLMの接続先URLを返す。
     * @param p 対象プロバイダー(Ollama / llama.cpp)
     * @return 保存済みURL。未設定(初回起動時)またはローカルLLM以外なら空文字
     */
    public String getServerUrl(RecipeAIService.Provider p) {
        String key = serverUrlKey(p);
        return key == null ? "" : prefs.getString(key, "");
    }

    /**
     * ローカルLLMの接続先URLを保存する。空文字なら削除して未設定に戻す。
     * @param p   対象プロバイダー(Ollama / llama.cpp。それ以外は何もしない)
     * @param url 保存するURL
     */
    public void setServerUrl(RecipeAIService.Provider p, String url) {
        String key = serverUrlKey(p);
        if (key == null) return;
        if (url == null || url.trim().isEmpty()) {
            prefs.edit().remove(key).apply();
        } else {
            prefs.edit().putString(key, url.trim()).apply();
        }
    }

    /**
     * @param p 対象プロバイダー
     * @return URLの保存キー。ローカルLLM以外は null
     */
    private static String serverUrlKey(RecipeAIService.Provider p) {
        if (p == RecipeAIService.Provider.LLAMA_CPP) return PREF_LLAMA_CPP_URL;
        if (p == RecipeAIService.Provider.OLLAMA) return PREF_OLLAMA_URL;
        return null;
    }

    /**
     * 現在のプロバイダーでAIを呼び出せる状態かを返す。
     * @return 必要なAPIキー / サーバーURL が揃っていれば true
     */
    public boolean isReady() {
        return RecipeAIService.isConfigured(provider, getApiKey(provider), getServerUrl(provider));
    }

    /**
     * 現在の設定(プロバイダー・APIキー・モデル・サーバーURL)で RecipeAIService を生成する。
     * @return 設定済みの RecipeAIService
     */
    public RecipeAIService createService() {
        return new RecipeAIService(provider, getApiKey(provider), getModel(provider), getServerUrl(provider));
    }
}
