package main.java.AI;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM で文字列を暗号化/復号するキーストア。
 * マスタ鍵はアプリ専用ファイル領域の .recipemanager/master.key に保存し、
 * 初回起動時に乱数で生成する(Android のアプリ専用領域は他アプリから読めない)。
 */
public class SecureKeyStore {

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;     // GCM 推奨
    private static final int KEY_BYTES = 32;    // AES-256

    private final SecretKey masterKey;
    private final SecureRandom random = new SecureRandom();

    /**
     * マスタ鍵をロードする(または初回時に新規生成する)。
     */
    public SecureKeyStore() {
        this.masterKey = loadOrCreateMasterKey();
    }

    /**
     * マスタ鍵ファイルのパスを返す。
     * Android では user.home が使えないため、アプリ専用ファイル領域(filesDir)を使う。
     * @return filesDir/.recipemanager/master.key の Path
     */
    private static Path keyFilePath() {
        File base = main.java.App.context().getFilesDir();
        return Paths.get(base.getAbsolutePath(), ".recipemanager", "master.key");
    }

    /**
     * マスタ鍵ファイルが存在すれば読み込み、無ければ32バイト乱数で新規生成して保存する。
     * @return AES-256 マスタ鍵
     */
    private SecretKey loadOrCreateMasterKey() {
        Path keyFile = keyFilePath();
        try {
            if (Files.exists(keyFile)) {
                byte[] bytes = Files.readAllBytes(keyFile);
                if (bytes.length != KEY_BYTES) {
                    throw new IOException("マスタ鍵ファイルのサイズが不正です: " + keyFile);
                }
                return new SecretKeySpec(bytes, "AES");
            }
            // 新規生成
            byte[] newKey = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(newKey);
            Files.createDirectories(keyFile.getParent());
            Files.write(keyFile, newKey);
            tryRestrictPermissions(keyFile);
            return new SecretKeySpec(newKey, "AES");
        } catch (Exception e) {
            throw new RuntimeException("マスタ鍵の準備に失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * 鍵ファイルを本人のみ読み書き可に制限する。
     * Linux/Mac: POSIX 0600。Windows: setReadable/setWritable のベストエフォート。
     * @param p パーミッションを設定するファイル
     */
    private static void tryRestrictPermissions(Path p) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
            return;
        } catch (UnsupportedOperationException | IOException ignore) {
            // POSIX 非対応の場合は下のWindows系フォールバック
        }
        File f = p.toFile();
        f.setReadable(false, false);
        f.setReadable(true, true);
        f.setWritable(false, false);
        f.setWritable(true, true);
    }

    /**
     * 平文をAES-GCMで暗号化し、(IV ‖ ciphertext) をBase64で返す。
     * @param plain 暗号化したい平文(null は空文字扱い)
     * @return Base64 エンコード済みの暗号文(IV12byte + 認証タグ16byte 含む)
     */
    public String encrypt(String plain) {
        if (plain == null) plain = "";
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
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
