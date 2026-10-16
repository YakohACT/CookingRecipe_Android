package main.java.AI;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;

/**
 * AES-256-GCM で文字列を暗号化/復号するキーストア。
 * デスクトップ版はマスタ鍵をファイル(~/.recipemanager/master.key)に保存するが、
 * Android 版は Android Keystore にマスタ鍵を生成・保管する(鍵はアプリから取り出せず、端末外へも持ち出せない)。
 * 暗号文の形式(Base64 の IV12byte ‖ ciphertext)はデスクトップ版と同じ。
 */
public class SecureKeyStore {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "recipemanager.master";
    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;     // GCM 推奨
    private static final int KEY_BITS = 256;    // AES-256

    private final SecretKey masterKey;

    /**
     * マスタ鍵をロードする(または初回時に Android Keystore 内で新規生成する)。
     */
    public SecureKeyStore() {
        this.masterKey = loadOrCreateMasterKey();
    }

    /**
     * Android Keystore にマスタ鍵があれば取得し、無ければ AES-256 鍵を生成する。
     * @return AES-256 マスタ鍵
     */
    private static SecretKey loadOrCreateMasterKey() {
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            if (ks.containsAlias(KEY_ALIAS)) {
                return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
            }
            KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    .build());
            return gen.generateKey();
        } catch (Exception e) {
            throw new RuntimeException("マスタ鍵の準備に失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * 平文をAES-GCMで暗号化し、(IV ‖ ciphertext) をBase64で返す。
     * Android Keystore の鍵は IV を自分で指定できないため、Cipher が生成した IV を使う。
     * @param plain 暗号化したい平文(null は空文字扱い)
     * @return Base64 エンコード済みの暗号文(IV12byte + 認証タグ16byte 含む)
     */
    public String encrypt(String plain) {
        if (plain == null) plain = "";
        try {
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.ENCRYPT_MODE, masterKey);
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new RuntimeException("暗号化失敗: " + e.getMessage(), e);
        }
    }

    /**
     * (IV ‖ ciphertext) をBase64でデコード→ AES-GCM 復号。
     * 失敗時(マスタ鍵不一致や改ざん検出時)は空文字を返す。
     * @param ciphertextB64 encrypt で生成された Base64 文字列
     * @return 復号された平文(失敗時は空文字)
     */
    public String decrypt(String ciphertextB64) {
        if (ciphertextB64 == null || ciphertextB64.isEmpty()) return "";
        try {
            byte[] all = Base64.getDecoder().decode(ciphertextB64);
            if (all.length <= IV_BYTES) return "";
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            byte[] ct = new byte[all.length - IV_BYTES];
            System.arraycopy(all, IV_BYTES, ct, 0, ct.length);
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
