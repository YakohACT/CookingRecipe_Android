package main.java.AI;

/**
 * YouTube に字幕が無い場合のフォールバック転写。
 * デスクトップ版は yt-dlp で音声を抽出し whisper.cpp(whisper-cli)で文字起こしするが、
 * Android では外部バイナリ(yt-dlp / ffmpeg / whisper-cli)を実行できないため常に無効とする。
 * 字幕・概要欄からの抽出と、Gemini の動画ネイティブ解析はそのまま利用できる。
 * 呼び出し側({@link YoutubeMetadataFetcher})をデスクトップ版と共通にするため、同じAPIを残している。
 */
public final class WhisperTranscriber {

    private WhisperTranscriber() {}

    /**
     * Whisper パイプラインが使えるかを返す。
     * @return Android では常に false
     */
    public static boolean isAvailable() {
        return false;
    }

    /**
     * YouTube URL から音声を抽出して文字起こしを行う。
     * @param youtubeUrl 対象動画のURL
     * @return Android では常に空文字
     */
    public static String transcribe(String youtubeUrl) {
        return "";
    }
}
