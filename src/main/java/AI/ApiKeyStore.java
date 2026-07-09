package main.java.AI;

import android.content.SharedPreferences;

/**
 * 各AIプロバイダー (OpenAI / Gemini / Claude) のAPIキーを
 * main.java.AI.SecureKeyStore で暗号化した上で SharedPreferences に永続化する。
 * (デスクトップ版の Java Preferences の Android 置き換え)
 *
 * SharedPreferences のキー形式: "apiKey.<PROVIDER>" (例: apiKey.OPENAI)
 */
public class ApiKeyStore {

    private static final String PREF_PREFIX = "apiKey.";

    private final SecureKeyStore secure;
    private final SharedPreferences prefs;

    /**
     * 暗号化エンジンと永続化先 SharedPreferences をDIする。
     * @param secure 暗号化/復号を担う SecureKeyStore
     * @param prefs  保存先 SharedPreferences
     */
    public ApiKeyStore(SecureKeyStore secure, SharedPreferences prefs) {
        this.secure = secure;
        this.prefs = prefs;
    }

    /**
     * 指定プロバイダーのAPIキーを取得する。未設定や復号失敗時は空文字を返す。
     * @param provider 取得したいプロバイダー
     * @return 復号済みのAPIキー(未設定/失敗時は空文字)
     */
    public String getKey(RecipeAIService.Provider provider) {
        if (provider == null) return "";
        String stored = prefs.getString(PREF_PREFIX + provider.name(), "");
        if (stored == null || stored.isEmpty()) return "";
        return secure.decrypt(stored);
    }

    /**
     * APIキーを暗号化して保存する。空文字を渡すとエントリを削除する。
     * @param provider 保存対象プロバイダー
     * @param plainKey 平文のAPIキー(空文字なら削除動作)
     */
    public void setKey(RecipeAIService.Provider provider, String plainKey) {
        if (provider == null) return;
        if (plainKey == null || plainKey.isEmpty()) {
            prefs.edit().remove(PREF_PREFIX + provider.name()).apply();
            return;
        }
        String encrypted = secure.encrypt(plainKey);
        prefs.edit().putString(PREF_PREFIX + provider.name(), encrypted).apply();
    }

    /** 全プロバイダーのキーを削除する(テストや初期化用) */
    public void clearAll() {
        SharedPreferences.Editor e = prefs.edit();
        for (RecipeAIService.Provider p : RecipeAIService.Provider.values()) {
            e.remove(PREF_PREFIX + p.name());
        }
        e.apply();
    }
}
