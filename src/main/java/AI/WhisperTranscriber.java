package main.java.AI;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * YouTube に字幕が無い場合のフォールバック転写。
 * yt-dlp で音声を 16kHz/mono WAV に抽出し、whisper.cpp(whisper-cli)で文字起こしする。
 *
 * ※ Android では外部バイナリ(yt-dlp / ffmpeg / whisper-cli)を実行できないため、
 *   {@link #isAvailable()} が常に false となり Whisper 転写は自動的に無効化される。
 *   (字幕・概要欄・Gemini の動画ネイティブ解析はそのまま利用できる)
 *
 * バイナリ探索順:
 *   1. lib/tools/ 配下に同梱された実行ファイル(Windowsは `xxx.exe`、UNIXは `xxx`)
 *   2. UNIX のみ: 環境変数 PATH(homebrew / apt 等で入れたシステム実行ファイル)
 *
 * 配置例:
 *   Windows  : lib/tools/yt-dlp.exe, ffmpeg.exe, whisper-cli.exe (setup-whisper.bat で取得)
 *   macOS/Linux: lib/tools/yt-dlp, ffmpeg, whisper-cli (setup-whisper.command で取得)
 *               または brew/apt で入れた /usr/local/bin/yt-dlp 等を自動検出
 *
 * モデルは lib/models/ggml-base.bin (Windows/UNIX 共通)。
 */
public final class WhisperTranscriber {

    /** 1動画あたりの最大処理時間。CPUでも完了するよう余裕を持たせる */
    private static final long TRANSCRIBE_TIMEOUT_SEC = 600; // 10分

    private WhisperTranscriber() {}

    /**
     * 現在のOSがWindowsかどうか。実行ファイル名の拡張子と環境変数キーの差異判定に使う。
     * @return Windows系OSなら true
     */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * プラットフォームごとの実行ファイル名を返す(Windowsだけ ".exe" を付与)。
     * @param baseName 拡張子なしのベース名(例: "yt-dlp")
     * @return 検索すべき実行ファイル名(例: Windowsなら "yt-dlp.exe")
     */
    private static String executableName(String baseName) {
        return isWindows() ? baseName + ".exe" : baseName;
    }

    /**
     * Whisper パイプラインの全構成要素が揃っているかを返す。
     * @return yt-dlp / ffmpeg / whisper-cli / モデルが全て見つかれば true
     */
    public static boolean isAvailable() {
        return locateTool("yt-dlp") != null
                && locateTool("ffmpeg") != null
                && locateWhisperCli() != null
                && locateModel() != null;
    }

    /**
     * YouTube URL から音声を抽出して文字起こしを行う。
     * 失敗時は空文字を返し、呼び出し側のフローを止めない。
     * @param youtubeUrl 対象動画のURL
     * @return 文字起こしテキスト(失敗時は空文字)
     */
    public static String transcribe(String youtubeUrl) {
        if (!isAvailable()) {
            System.err.println("[WhisperTranscriber] パイプライン未セットアップ: "
                    + (isWindows() ? "setup-whisper.bat" : "scripts/unix/setup-whisper.command")
                    + " を実行するか、yt-dlp / ffmpeg / whisper-cli を PATH に通してください");
            return "";
        }
        if (youtubeUrl == null || youtubeUrl.trim().isEmpty()) return "";

        Path ytDlp       = locateTool("yt-dlp");
        Path ffmpegExe   = locateTool("ffmpeg");
        Path whisperCli  = locateWhisperCli();
        Path modelFile   = locateModel();
        Path toolsDir    = ytDlp.getParent();

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("recipe-whisper-");
            Path audioStem = tempDir.resolve("audio");

            // === 1. yt-dlp で 16kHz mono WAV を取得 =======================
            // --postprocessor-args は ffmpeg に渡される
            int rc = runProcess(toolsDir, TRANSCRIBE_TIMEOUT_SEC,
                    ytDlp.toString(),
                    "-x",
                    "--audio-format", "wav",
                    "--ffmpeg-location", ffmpegExe.getParent().toString(),
                    "--postprocessor-args", "-ar 16000 -ac 1",
                    "--no-playlist",
                    "--quiet",
                    "-o", audioStem + ".%(ext)s",
                    youtubeUrl);
            if (rc != 0) {
                System.err.println("[WhisperTranscriber] yt-dlp 失敗 (rc=" + rc + ")");
                return "";
            }

            Path wav = tempDir.resolve("audio.wav");
            if (!Files.exists(wav)) {
                System.err.println("[WhisperTranscriber] 音声ファイルが生成されませんでした: " + wav);
                return "";
            }

            // === 2. whisper-cli で文字起こし =============================
            // -otxt で <wavPath>.txt が出力される
            rc = runProcess(toolsDir, TRANSCRIBE_TIMEOUT_SEC,
                    whisperCli.toString(),
                    "-m", modelFile.toString(),
                    "-f", wav.toString(),
                    "-l", "auto",        // 言語自動判定
                    "-otxt",
                    "-nt");              // タイムスタンプ非表示でテキストだけにする
            if (rc != 0) {
                System.err.println("[WhisperTranscriber] whisper-cli 失敗 (rc=" + rc + ")");
                return "";
            }

            Path outTxt = Paths.get(wav.toString() + ".txt");
            if (!Files.exists(outTxt)) {
                System.err.println("[WhisperTranscriber] 文字起こし結果ファイルが見つかりません: " + outTxt);
                return "";
            }

            // Files.readString は Android API 33 未満に無いため readAllBytes を使う
            return new String(Files.readAllBytes(outTxt), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            System.err.println("[WhisperTranscriber] 例外: " + e.getMessage());
            return "";
        } finally {
            if (tempDir != null) deleteRecursively(tempDir);
        }
    }

    /**
     * 実行ファイルを引数列で起動して終了を待つ。
     * 標準出力は破棄、エラーストリームのみコンソールへ転送する。
     * @param workingDir   起動時の作業ディレクトリ(PATH 先頭にも追加される)
     * @param timeoutSec   タイムアウト秒数
     * @param command      実行コマンドと引数
     * @return 終了コード(タイムアウト時は -1)
     */
    private static int runProcess(Path workingDir, long timeoutSec, String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        // PATH を先頭に追加して、yt-dlp が ffmpeg を発見できるようにする。
        // Windowsは "Path"(case-insensitive)、UNIX は "PATH"(case-sensitive) を使う必要がある。
        Map<String, String> env = pb.environment();
        String pathKey = isWindows() ? "Path" : "PATH";
        String oldPath = env.getOrDefault(pathKey, env.getOrDefault("PATH", ""));
        env.put(pathKey, workingDir.toString() + File.pathSeparator + oldPath);
        // 標準出力は破棄(Redirect.DISCARD は Android SDK に無いため null デバイスへ書き込む)。
        // 標準エラーは inherit してデバッグ実行時に見えるように
        pb.redirectOutput(ProcessBuilder.Redirect.to(new File(isWindows() ? "NUL" : "/dev/null")));
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        Process p = pb.start();
        boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            return -1;
        }
        return p.exitValue();
    }

    // ---- 配置検出 ----------------------------------------------------------

    /**
     * 実行ファイルを次の順で探す:
     *   1. lib/tools/ 配下の候補ディレクトリ(Windowsは ".exe" 付き、UNIX は拡張子なし)
     *   2. UNIX のみ: 環境変数 PATH 内のディレクトリ
     * @param baseName 拡張子なしのベース名(例: "yt-dlp")
     * @return 見つかった Path。見つからなければ null
     */
    private static Path locateTool(String baseName) {
        String exeName = executableName(baseName);
        // 1. 同梱バイナリ
        for (Path base : toolsDirCandidates()) {
            Path p = base.resolve(exeName);
            if (Files.exists(p)) return p.toAbsolutePath();
        }
        // 2. UNIX のみシステム PATH もフォールバックとして検索(brew/apt インストール対応)
        if (!isWindows()) {
            Path fromPath = findOnSystemPath(exeName);
            if (fromPath != null) return fromPath;
        }
        return null;
    }

    /**
     * 環境変数 PATH を順に走査し、実行可能ファイルを返す(UNIX用)。
     * @param exeName 検索する実行ファイル名(プラットフォーム拡張子込み)
     * @return 見つかった Path。見つからなければ null
     */
    private static Path findOnSystemPath(String exeName) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) return null;
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            Path p = Paths.get(dir, exeName);
            if (Files.exists(p) && Files.isExecutable(p)) return p.toAbsolutePath();
        }
        return null;
    }

    /**
     * whisper-cli を探す。バイナリ名のバリエーション(whisper-cli / whisper-cpp / 旧main)に対応。
     * @return 見つかった Path。見つからなければ null
     */
    private static Path locateWhisperCli() {
        Path p = locateTool("whisper-cli");
        if (p != null) return p;
        // Homebrew whisper-cpp フォーミュラなど別名で入る場合がある
        p = locateTool("whisper-cpp");
        if (p != null) return p;
        // 古い whisper.cpp ビルドの "main" バイナリ。lib/tools/ のみ対象
        // (UNIXの PATH 上の "main" は別物の可能性が高いので意図的に検索しない)
        return locateBundledTool("main");
    }

    /**
     * 同梱バイナリ(lib/tools/)のみを探す(PATHは見ない)。
     * 名前が汎用的なバイナリ("main"等)で誤検出を避けたいときに使う。
     * @param baseName ベース名
     * @return 見つかった Path。見つからなければ null
     */
    private static Path locateBundledTool(String baseName) {
        String exeName = executableName(baseName);
        for (Path base : toolsDirCandidates()) {
            Path p = base.resolve(exeName);
            if (Files.exists(p)) return p.toAbsolutePath();
        }
        return null;
    }

    /**
     * ggml-*.bin モデルを探す。base を優先、無ければサイズ違いも許容。
     * @return 見つかった Path。見つからなければ null
     */
    private static Path locateModel() {
        for (Path base : modelDirCandidates()) {
            for (String name : new String[]{
                    "ggml-base.bin", "ggml-small.bin", "ggml-medium.bin",
                    "ggml-tiny.bin", "ggml-large.bin"
            }) {
                Path p = base.resolve(name);
                if (Files.exists(p)) return p.toAbsolutePath();
            }
        }
        return null;
    }

    /**
     * lib/tools の候補パス一覧。実行ディレクトリ違いに対応。
     * @return パスの配列
     */
    private static Path[] toolsDirCandidates() {
        return new Path[]{
                Paths.get("lib/tools"),
                Paths.get("CookingRecipe/lib/tools"),
                Paths.get("../lib/tools"),
                Paths.get("../../lib/tools")
        };
    }

    /**
     * lib/models の候補パス一覧。
     * @return パスの配列
     */
    private static Path[] modelDirCandidates() {
        return new Path[]{
                Paths.get("lib/models"),
                Paths.get("CookingRecipe/lib/models"),
                Paths.get("../lib/models"),
                Paths.get("../../lib/models")
        };
    }

    /**
     * 一時ディレクトリ配下を再帰的に削除する(失敗は黙殺)。
     * @param root 削除対象のルートパス
     */
    private static void deleteRecursively(Path root) {
        try {
            Files.walk(root)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignore) {} });
        } catch (IOException ignore) {}
    }
}
