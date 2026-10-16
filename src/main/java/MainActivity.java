package main.java;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.example.recipemanager.R;

import main.java.AI.AiSettings;
import main.java.Recipe.AllRecipeList;
import main.java.Recipe.IngredientMaster;
import main.java.UI.DataIOPanel;
import main.java.UI.IngredientMasterPanel;
import main.java.UI.RegisterRecipePanel;
import main.java.UI.SettingsPanel;
import main.java.UI.Theme;
import main.java.UI.UIComponents;
import main.java.UI.ViewRecipePanel;

import java.util.function.Consumer;

/**
 * レシピ管理アプリのメイン画面(デスクトップ版 SwingMain の置き換え)。
 * サイドメニュー(ドロワー)構築と中央パネルの切替、共有状態(レシピ・食材・AI設定)の保持、
 * テーマ(Light/Dark)の切替を担当する。
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREF_THEME = "theme";

    private SharedPreferences prefs;

    private AllRecipeList allRecipeList;
    private IngredientMaster ingredientMaster;
    private AiSettings aiSettings;

    private boolean darkMode;
    private Button themeButton;

    private DrawerLayout drawer;
    private ActionBarDrawerToggle drawerToggle;
    private FrameLayout centerPanel;
    /** ウェルカム画面を表示中か(戻るボタンでウェルカム画面へ戻すかの判定に使う) */
    private boolean showingWelcome;

    /** SAF のファイル選択結果を受け取るランチャー(パネル側から pickFile 経由で利用) */
    private ActivityResultLauncher<Intent> fileLauncher;
    private Consumer<Uri> pendingFileCallback;

    /**
     * 画面を構築する。
     * 食材マスタ・レシピDB・AI設定を順に初期化し、
     * 前回のテーマを復元してウェルカム画面を表示する。
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = getSharedPreferences("RecipeManager", MODE_PRIVATE);

        // 起動時に保存されたテーマを適用(無ければLight)
        darkMode = "dark".equals(prefs.getString(PREF_THEME, "light"));
        AppCompatDelegate.setDefaultNightMode(
                darkMode ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);

        super.onCreate(savedInstanceState);

        // IngredientMaster を先に初期化し、AllRecipeList(SQLite読込)の食材名解決に渡す
        ingredientMaster = new IngredientMaster();
        allRecipeList = new AllRecipeList(ingredientMaster);
        aiSettings = new AiSettings(prefs);

        fileLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Consumer<Uri> cb = pendingFileCallback;
                    pendingFileCallback = null;
                    if (cb != null && result.getResultCode() == RESULT_OK
                            && result.getData() != null && result.getData().getData() != null) {
                        cb.accept(result.getData().getData());
                    }
                });

        // 戻るボタン: ドロワーを閉じる → ウェルカム画面へ戻る → アプリを閉じる の順
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawer.isDrawerOpen(GravityCompat.START)) {
                    drawer.closeDrawer(GravityCompat.START);
                } else if (!showingWelcome) {
                    showWelcome();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                }
            }
        });

        buildUi();
        showWelcome();
    }

    /**
     * DrawerLayout(サイドメニュー) + 中央パネルの画面骨格を構築する。
     */
    private void buildUi() {
        drawer = new DrawerLayout(this);

        centerPanel = new FrameLayout(this);
        drawer.addView(centerPanel, new DrawerLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView menuScroll = new ScrollView(this);
        menuScroll.setBackgroundColor(Theme.COLOR_SIDE);
        menuScroll.addView(buildSideMenu(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        DrawerLayout.LayoutParams menuLp = new DrawerLayout.LayoutParams(
                UIComponents.dp(this, 240), ViewGroup.LayoutParams.MATCH_PARENT);
        menuLp.gravity = GravityCompat.START;
        drawer.addView(menuScroll, menuLp);

        setContentView(drawer);

        setTitle(R.string.app_name);
        drawerToggle = new ActionBarDrawerToggle(this, drawer, R.string.drawer_open, R.string.drawer_close);
        drawer.addDrawerListener(drawerToggle);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeButtonEnabled(true);
        }
        drawerToggle.syncState();
    }

    /**
     * サイドメニュー(MENU + 各操作ボタン + テーマ切替 + 終了)を構築する。
     * @return サイドメニュービュー
     */
    private LinearLayout buildSideMenu() {
        LinearLayout sideMenu = new LinearLayout(this);
        sideMenu.setOrientation(LinearLayout.VERTICAL);
        sideMenu.setBackgroundColor(Theme.COLOR_SIDE);
        int pad = UIComponents.dp(this, 10);
        sideMenu.setPadding(pad, UIComponents.dp(this, 30), pad, pad);

        TextView menuTitle = new TextView(this);
        menuTitle.setText("MENU");
        menuTitle.setTextColor(Theme.COLOR_TEXT_LIGHT);
        menuTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, Theme.TEXT_SIZE_TITLE);
        menuTitle.setTypeface(Typeface.DEFAULT_BOLD);
        menuTitle.setGravity(Gravity.CENTER_HORIZONTAL);
        sideMenu.addView(menuTitle, menuLp());
        UIComponents.addSpacer(sideMenu, 30);

        addSideButton(sideMenu, "レシピ登録", v -> showPanel(new RegisterRecipePanel(this)));
        addSideButton(sideMenu, "レシピ閲覧", v -> showPanel(new ViewRecipePanel(this)));
        addSideButton(sideMenu, "AI設定",   v -> showPanel(new SettingsPanel(this)));
        addSideButton(sideMenu, "食材管理", v -> showPanel(new IngredientMasterPanel(this)));
        addSideButton(sideMenu, "データ入出力", v -> showPanel(new DataIOPanel(this)));

        UIComponents.addSpacer(sideMenu, 30);

        themeButton = createSideButton(themeButtonLabel(), v -> toggleTheme());
        sideMenu.addView(themeButton, menuLp());
        UIComponents.addSpacer(sideMenu, 10);

        addSideButton(sideMenu, "終了", v -> finishAffinity());

        return sideMenu;
    }

    /**
     * サイドメニューにボタンを追加し、下にスペーサを置く。
     * @param parent 追加先のサイドメニュー
     * @param text   ボタンに表示する文字列
     * @param action クリック時の動作
     */
    private void addSideButton(LinearLayout parent, String text, View.OnClickListener action) {
        parent.addView(createSideButton(text, action), menuLp());
        UIComponents.addSpacer(parent, 10);
    }

    /**
     * サイドメニュー用のボタンを生成する。クリック時にドロワーを閉じる。
     * @param text   ボタンラベル
     * @param action クリック時の動作
     * @return スタイル適用済みの Button
     */
    private Button createSideButton(String text, View.OnClickListener action) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextColor(Theme.COLOR_TEXT_LIGHT);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, Theme.TEXT_SIZE_MAIN);
        btn.setBackgroundTintList(ColorStateList.valueOf(Theme.COLOR_SIDE));
        btn.setOnClickListener(v -> {
            drawer.closeDrawer(GravityCompat.START);
            action.onClick(v);
        });
        return btn;
    }

    /**
     * @return サイドメニュー用の横幅いっぱいの LayoutParams
     */
    private static LinearLayout.LayoutParams menuLp() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /**
     * テーマ切替ボタンのラベルを現在の状態(darkMode)に応じて返す。
     * @return ボタンに表示するラベル文字列
     */
    private String themeButtonLabel() {
        return darkMode ? "☀ ライトモード" : "☾ ダークモード";
    }

    /** Light/Dark を切替えて保存する(DayNight テーマの切替で画面が作り直される) */
    public void toggleTheme() {
        darkMode = !darkMode;
        prefs.edit().putString(PREF_THEME, darkMode ? "dark" : "light").apply();
        if (themeButton != null) themeButton.setText(themeButtonLabel());
        AppCompatDelegate.setDefaultNightMode(
                darkMode ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
    }

    /**
     * 中央パネルを差し替えて表示する。
     * @param panel 表示したいビュー
     */
    public void showPanel(View panel) {
        showingWelcome = false;
        centerPanel.removeAllViews();
        centerPanel.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** ウェルカム画面を中央パネルに表示する */
    public void showWelcome() {
        TextView label = new TextView(this);
        label.setText("Welcome!\n操作を選択してください\n\n(左上のメニューから画面を開けます)");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, Theme.TEXT_SIZE_TITLE);
        label.setGravity(Gravity.CENTER);
        showPanel(label);
        showingWelcome = true;
    }

    /**
     * SAF のファイル選択/作成 Intent を起動し、結果の Uri をコールバックへ渡す
     * (JFileChooser の置き換え)。
     * @param intent   ACTION_OPEN_DOCUMENT / ACTION_CREATE_DOCUMENT の Intent
     * @param callback 選択された Uri を受け取る処理(キャンセル時は呼ばれない)
     */
    public void pickFile(Intent intent, Consumer<Uri> callback) {
        pendingFileCallback = callback;
        fileLauncher.launch(intent);
    }

    /**
     * 画面回転などは manifest の configChanges で自前処理するため、ドロワーのアイコン状態だけ追従させる。
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (drawerToggle != null) drawerToggle.onConfigurationChanged(newConfig);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (drawerToggle != null && drawerToggle.onOptionsItemSelected(item)) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * @return 全レシピ管理オブジェクト
     */
    public AllRecipeList getAllRecipeList() { return allRecipeList; }

    /**
     * @return 食材マスタ
     */
    public IngredientMaster getIngredientMaster() { return ingredientMaster; }

    /**
     * @return AI利用設定(プロバイダー・APIキー・モデル等)
     */
    public AiSettings getAiSettings() { return aiSettings; }
}
