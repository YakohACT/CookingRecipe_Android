package main.java.UI;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import main.java.Recipe.Ingredient;
import main.java.Recipe.Recipe;
import main.java.Recipe.RecipeCategory;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 各画面で共通利用するAndroid UIコンポーネントの生成ヘルパー。
 * (デスクトップ版の Swing ヘルパーの置き換え。役割はほぼ同一)
 */
public final class UIComponents {

    /** バックグラウンド処理用スレッドプール(SwingWorker の置き換え) */
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    /** メインスレッドへのポスト用ハンドラ */
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private UIComponents() {}

    /**
     * dp 値をピクセルに変換する。
     * @param context コンテキスト
     * @param dp      dp 値
     * @return ピクセル値
     */
    public static int dp(Context context, int dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics()));
    }

    /**
     * バックグラウンドで task を実行し、結果をメインスレッドのコールバックへ渡す。
     * (SwingWorker#doInBackground/done の置き換え)
     * @param task      バックグラウンドで実行する処理
     * @param onSuccess 成功時コールバック(メインスレッド)
     * @param onError   失敗時コールバック(メインスレッド)
     * @param <T>       結果の型
     */
    public static <T> void runAsync(Callable<T> task, Consumer<T> onSuccess, Consumer<Exception> onError) {
        EXECUTOR.execute(() -> {
            try {
                T result = task.call();
                MAIN_HANDLER.post(() -> onSuccess.accept(result));
            } catch (Exception e) {
                MAIN_HANDLER.post(() -> onError.accept(e));
            }
        });
    }

    /**
     * メインスレッドで処理を実行する(バックグラウンドスレッドからのUI更新用)。
     * @param action 実行する処理
     */
    public static void postToMain(Runnable action) {
        MAIN_HANDLER.post(action);
    }

    /**
     * 情報ダイアログを表示する(JOptionPane.showMessageDialog の置き換え)。
     * @param context コンテキスト
     * @param message 表示するメッセージ
     */
    public static void info(Context context, String message) {
        new AlertDialog.Builder(context)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    /**
     * タイトル付き情報ダイアログを表示する。
     * @param context コンテキスト
     * @param title   ダイアログタイトル
     * @param message 表示するメッセージ
     */
    public static void info(Context context, String title, String message) {
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    /**
     * OK/キャンセルの確認ダイアログを表示する(JOptionPane.showConfirmDialog の置き換え)。
     * @param context コンテキスト
     * @param title   ダイアログタイトル
     * @param message 確認メッセージ
     * @param onOk    OK 押下時の処理
     */
    public static void confirm(Context context, String title, String message, Runnable onOk) {
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", (d, w) -> onOk.run())
                .setNegativeButton("キャンセル", null)
                .show();
    }

    /**
     * テーマフォントサイズを設定済みの1行入力欄を生成する。
     * (コピー/貼り付けは Android 標準の長押しメニューが利用できる)
     * @param context コンテキスト
     * @return スタイル適用済みの EditText
     */
    public static EditText createStyledTextField(Context context) {
        EditText field = new EditText(context);
        field.setSingleLine(true);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, Theme.TEXT_SIZE_MAIN);
        field.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return field;
    }

    /**
     * 通常ラベルを生成する。
     * @param context コンテキスト
     * @param text    表示文字列
     * @return スタイル適用済みの TextView
     */
    public static TextView createLabel(Context context, String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, Theme.TEXT_SIZE_MAIN);
        return label;
    }

    /**
     * 画面タイトル用の太字ラベルを生成する。
     * @param context コンテキスト
     * @param text    表示文字列
     * @return スタイル適用済みの TextView
     */
    public static TextView createTitleLabel(Context context, String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, Theme.TEXT_SIZE_TITLE);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        return label;
    }

    /**
     * テーマのプライマリ色を背景にした強調ボタンを生成する。
     * @param context コンテキスト
     * @param text    ボタンに表示するラベル
     * @return スタイル適用済みの Button
     */
    public static Button createPrimaryButton(Context context, String text) {
        return createColoredButton(context, text, Theme.COLOR_PRIMARY);
    }

    /**
     * 指定色を背景・白文字のボタンを生成する。
     * @param context コンテキスト
     * @param text    ボタンに表示するラベル
     * @param color   背景色(ARGB)
     * @return スタイル適用済みの Button
     */
    public static Button createColoredButton(Context context, String text, int color) {
        Button btn = new Button(context);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextColor(Theme.COLOR_TEXT_LIGHT);
        btn.setBackgroundTintList(ColorStateList.valueOf(color));
        return btn;
    }

    /**
     * レシピ一覧表示用の ArrayAdapter を生成する(タイトルを表示、選択でハイライト)。
     * ListView 側で {@code setChoiceMode(ListView.CHOICE_MODE_SINGLE)} を設定すること。
     * @param context コンテキスト
     * @param recipes 表示するレシピリスト(参照を保持し、変更後は notifyDataSetChanged で反映)
     * @return レシピ用アダプタ
     */
    public static ArrayAdapter<Recipe> createRecipeAdapter(Context context, List<Recipe> recipes) {
        return new ArrayAdapter<Recipe>(context, android.R.layout.simple_list_item_activated_1, recipes) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                Recipe r = getItem(position);
                v.setText(r == null ? "" : r.getTitle());
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, Theme.TEXT_SIZE_MAIN);
                return v;
            }
        };
    }

    /**
     * 任意の値配列から Spinner 用アダプタを生成する。
     * @param context コンテキスト
     * @param values  表示する値
     * @param <T>     値の型(toString() が表示に使われる)
     * @return Spinner 用アダプタ
     */
    public static <T> ArrayAdapter<T> createSpinnerAdapter(Context context, T[] values) {
        ArrayAdapter<T> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    /**
     * 任意の値リストから Spinner 用アダプタを生成する。
     * @param context コンテキスト
     * @param values  表示する値リスト
     * @param <T>     値の型(toString() が表示に使われる)
     * @return Spinner 用アダプタ
     */
    public static <T> ArrayAdapter<T> createSpinnerAdapter(Context context, List<T> values) {
        ArrayAdapter<T> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    /**
     * Spinner の現在の選択値を返す(型安全ヘルパー)。
     * @param spinner 対象の Spinner
     * @param <T>     期待する型
     * @return 選択中の値(未選択なら null)
     */
    @SuppressWarnings("unchecked")
    public static <T> T selectedItem(Spinner spinner) {
        Object item = spinner.getSelectedItem();
        return (T) item;
    }

    /**
     * レシピ詳細表示用の TextView を生成する。
     * 中の URL はリンクとしてタップ可能で、標準ブラウザで開く。
     * @param context コンテキスト
     * @return 詳細表示用 TextView
     */
    public static TextView createDetailArea(Context context) {
        TextView area = new TextView(context);
        area.setTextSize(TypedValue.COMPLEX_UNIT_SP, Theme.TEXT_SIZE_MAIN);
        int pad = dp(context, 16);
        area.setPadding(pad, pad, pad, pad);
        // <a href> リンクをタップでブラウザ起動できるようにする
        area.setMovementMethod(LinkMovementMethod.getInstance());
        // 長押しでテキスト選択・コピーができるようにする(右クリックメニューの置き換え)
        area.setTextIsSelectable(true);
        return area;
    }

    /**
     * 詳細エリアの内容を指定レシピで再描画する。
     * @param area   表示先の TextView
     * @param recipe 表示するレシピ
     */
    public static void updateDetailArea(TextView area, Recipe recipe) {
        String ings = recipe.getIngredients().stream()
                .map(Ingredient::getName)
                .collect(Collectors.joining(", "));
        String categories = recipe.getCategories().stream()
                .map(RecipeCategory::getDisplayName)
                .collect(Collectors.joining(", "));
        String url = recipe.getUrl() == null ? "" : recipe.getUrl();

        String html = "<b>【レシピ名】</b><br>" + escapeHtml(recipe.getTitle()) + "<br><br>"
                + "<b>【カテゴリー】</b><br>" + escapeHtml(categories) + "<br><br>"
                + "<b>【URL】</b><br>"
                + (url.isEmpty()
                    ? ""
                    : "<a href=\"" + escapeAttr(url) + "\">" + escapeHtml(url) + "</a>")
                + "<br><br>"
                + "<b>【必要食材】</b><br>" + escapeHtml(ings);
        area.setText(Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT));
    }

    /**
     * HTMLテキスト埋め込み用に &amp;/&lt;/&gt; をエスケープする。
     * @param s 任意の文字列(null可)
     * @return エスケープ後の文字列
     */
    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * HTML属性値用に追加で {@code "} をエスケープする。
     * @param s 任意の文字列(null可)
     * @return エスケープ後の文字列
     */
    private static String escapeAttr(String s) {
        if (s == null) return "";
        return escapeHtml(s).replace("\"", "&quot;");
    }
}
