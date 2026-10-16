package main.java.UI;

/**
 * UIで使用する色とフォントサイズの定数を集約するクラス(Android版)
 * 色は ARGB int、フォントサイズは sp 単位で保持する
 */
public final class Theme {
    public static final int COLOR_PRIMARY = 0xFF3498DB;
    public static final int COLOR_SIDE = 0xFF2C3E50;
    public static final int COLOR_TEXT_LIGHT = 0xFFFFFFFF;
    public static final int COLOR_DELETE = 0xFFE74C3C;
    public static final int COLOR_AI = 0xFF9B59B6;
    /** 非選択タブ・補足文字などの控えめな色 */
    public static final int COLOR_MUTED = 0xFF9E9E9E;

    /** 本文用フォントサイズ (sp) */
    public static final float TEXT_SIZE_MAIN = 14f;
    /** 画面内の見出し用フォントサイズ (sp) */
    public static final float TEXT_SIZE_HEADER = 16f;
    /** タイトル用フォントサイズ (sp) */
    public static final float TEXT_SIZE_TITLE = 18f;

    private Theme() {}
}
