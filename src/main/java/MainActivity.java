package main.java;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.example.recipemanager.R;

import main.java.AI.ApiKeyStore;
import main.java.AI.Ollama.OllamaProvider;
import main.java.AI.RecipeAIService;
import main.java.AI.SecureKeyStore;
import main.java.Recipe.AllRecipeList;
import main.java.Recipe.IngredientMaster;
import main.java.UI.DataIOPanel;
import main.java.UI.DeleteRecipePanel;
import main.java.UI.IngredientMasterPanel;
import main.java.UI.RegisterRecipePanel;
import main.java.UI.SettingsPanel;
import main.java.UI.Theme;
import main.java.UI.UIComponents;
import main.java.UI.ViewRecipePanel;

import java.util.function.Consumer;

/**
 * レシピ管理アプリのメイン画面(デスクトップ版 SwingMain の置き換え)。
 * ドロワーメニュー構築と中央パネルの切替、共有状態(レシピ・食材・AI設定)の保持、
 * テーマ(Light/Dark)の切替を担当する。
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREF_THEME = "theme";
    private static final String PREF_OLLAMA_URL = "ollamaUrl";

    private SharedPreferences prefs;

    private AllRecipeList allRecipeList;
    private IngredientMaster ingredientMaster;
    private ApiKeyStore apiKeyStore;

    private RecipeAIService.Provider currentAiProvider = RecipeAIService.Provider.OPENAI;

    private boolean darkMode;
    private Button themeButton;

    private DrawerLayout drawer;
    private ActionBarDrawerToggle drawerToggle;
    private FrameLayout centerPanel;

    /** SAF のファイル選択結果を受け取るランチャー(パネル側から pickFile 経由で利用) */
    private ActivityResultLauncher<Intent> fileLauncher;
    private Consumer<Uri> pendingFileCallback;

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
        apiKeyStore = new ApiKeyStore(new SecureKeyStore(), prefs);

        // 前回選択していたプロバイダーを復元
        String savedProvider = prefs.getString("aiProvider", "");
        if (savedProvider != null && !savedProvider.isEmpty()) {
            try {
                currentAiProvider = RecipeAIService.Provider.valueOf(savedProvider);
            } catch (IllegalArgumentException ignore) {}
        }

        // Ollama の接続先URLを復元(未設定なら既定の localhost)
        OllamaProvider.setBaseUrl(prefs.getString(PREF_OLLAMA_URL, ""));

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

        setTitle("Recipe Manager Pro");
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
        sideMenu.addView(menuTitle, menuLp(0));
        addSpacer(sideMenu, 30);

        addSideButton(sideMenu, "レシピ登録", v -> showPanel(new RegisterRecipePanel(this)));
        addSideButton(sideMenu, "レシピ閲覧", v -> showPanel(new ViewRecipePanel(this)));
        addSideButton(sideMenu, "レシピ削除", v -> showPanel(new DeleteRecipePanel(this)));
        addSideButton(sideMenu, "AI設定",   v -> showPanel(new SettingsPanel(this)));
        addSideButton(sideMenu, "食材管理", v -> showPanel(new IngredientMasterPanel(this)));
        addSideButton(sideMenu, "データ入出力", v -> showPanel(new DataIOPanel(this)));

        addSpacer(sideMenu, 30);

        themeButton = createSideButton(themeButtonLabel(), v -> toggleTheme());
        sideMenu.addView(themeButton, menuLp(0));
        addSpacer(sideMenu, 10);

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
        Button btn = createSideButton(text, action);
        parent.addView(btn, menuLp(0));
        addSpacer(parent, 10);
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
     * サイドメニュー用の LayoutParams を生成する。
     * @param unusedHeightDp 未使用(将来の高さ指定用)
     * @return 横幅いっぱいの LayoutParams
     */
    private LinearLayout.LayoutParams menuLp(int unusedHeightDp) {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /**
     * 縦方向スペーサを追加する。
     * @param parent   追加先
     * @param heightDp スペーサの高さ(dp)
     */
    private void addSpacer(LinearLayout parent, int heightDp) {
        View spacer = new View(this);
        parent.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UIComponents.dp(this, heightDp)));
    }

    /**
     * テーマ切替ボタンのラベルを現在の状態(darkMode)に応じて返す。
     * @return ボタンに表示するラベル文字列
     */
    private String themeButtonLabel() {
        return darkMode ? "☀ ライトモード" : "☾ ダークモード";
    }

    /** Light/Dark を切替えて画面全体を再生成する */
    public void toggleTheme() {
        darkMode = !darkMode;
        prefs.edit().putString(PREF_THEME, darkMode ? "dark" : "light").apply();
        if (themeButton != null) themeButton.setText(themeButtonLabel());
        // DayNight テーマの切替(Activity が自動的に再生成される)
        AppCompatDelegate.setDefaultNightMode(
                darkMode ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
    }

    /**
     * 中央パネルを差し替えて表示する。
     * @param panel 表示したいビュー
     */
    public void showPanel(View panel) {
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
    }

    /**
     * SAF のファイル選択/作成 Intent を起動し、結果の Uri をコールバックへ渡す。
     * @param intent   ACTION_OPEN_DOCUMENT / ACTION_CREATE_DOCUMENT の Intent
     * @param callback 選択された Uri を受け取る処理(キャンセル時は呼ばれない)
     */
    public void pickFile(Intent intent, Consumer<Uri> callback) {
        pendingFileCallback = callback;
        fileLauncher.launch(intent);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (drawerToggle != null && drawerToggle.onOptionsItemSelected(item)) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (drawer != null && drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
            return;
        }
        super.onBackPressed();
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
     * @return 現在選択中のAIプロバイダー
     */
    public RecipeAIService.Provider getCurrentAiProvider() { return currentAiProvider; }

    /**
     * 現在のAIプロバイダーを切替えて SharedPreferences に保存する。
     * @param p 切替先のプロバイダー
     */
    public void setCurrentAiProvider(RecipeAIService.Provider p) {
        this.currentAiProvider = p;
        if (p != null) prefs.edit().putString("aiProvider", p.name()).apply();
    }

    /**
     * 現在選択中プロバイダーの復号済みAPIキーを返す。
     * @return APIキー(未設定または復号失敗時は空文字)
     */
    public String getCurrentApiKey() { return apiKeyStore.getKey(currentAiProvider); }

    /**
     * 指定プロバイダーのAPIキー(復号済み)を返す。
     * @param p 対象プロバイダー
     * @return APIキー(未設定時は空文字)
     */
    public String getApiKeyFor(RecipeAIService.Provider p) { return apiKeyStore.getKey(p); }

    /**
     * 指定プロバイダーのAPIキーを暗号化して保存する(空文字なら削除)。
     * @param p        対象プロバイダー
     * @param plainKey 平文のAPIキー(空文字で削除)
     */
    public void setApiKeyFor(RecipeAIService.Provider p, String plainKey) { apiKeyStore.setKey(p, plainKey); }

    /**
     * 現在選択中プロバイダーの利用モデル名を返す。
     * 保存値が無い場合はそのプロバイダーの先頭モデル(getAvailableModels()[0])にフォールバック。
     * @return 利用モデル名(未設定時は先頭モデル)
     */
    public String getCurrentModelName() { return getModelFor(currentAiProvider); }

    /**
     * 指定プロバイダーで保存済みのモデル名を返す。
     * @param p 対象プロバイダー
     * @return 保存値があればそれ、無ければそのプロバイダーの先頭モデル
     */
    public String getModelFor(RecipeAIService.Provider p) {
        if (p == null) return "";
        String saved = prefs.getString("aiModel." + p.name(), "");
        if (saved != null && !saved.isEmpty()) return saved;
        // 未設定のときはそのプロバイダーの最初の利用可能モデルにフォールバック
        String[] models = new RecipeAIService().getModelsForProvider(p);
        return models.length > 0 ? models[0] : "";
    }

    /**
     * 指定プロバイダーの利用モデル名を保存する。空文字なら削除動作。
     * @param p     対象プロバイダー
     * @param model 保存するモデル名(空文字で削除)
     */
    public void setModelFor(RecipeAIService.Provider p, String model) {
        if (p == null) return;
        if (model == null || model.isEmpty()) {
            prefs.edit().remove("aiModel." + p.name()).apply();
        } else {
            prefs.edit().putString("aiModel." + p.name(), model).apply();
        }
    }

    /**
     * 互換用: 現在のプロバイダーに対してモデルを保存する。
     * @param m 保存するモデル名
     */
    public void setCurrentModelName(String m) { setModelFor(currentAiProvider, m); }

    /**
     * 保存済みの Ollama サーバーURLを返す。
     * @return 保存値(未設定なら空文字)
     */
    public String getOllamaUrl() { return prefs.getString(PREF_OLLAMA_URL, ""); }

    /**
     * Ollama サーバーURLを保存し、OllamaProvider へ即時反映する。
     * @param url 接続先ベースURL(空文字で既定の localhost に戻す)
     */
    public void setOllamaUrl(String url) {
        String v = url == null ? "" : url.trim();
        prefs.edit().putString(PREF_OLLAMA_URL, v).apply();
        OllamaProvider.setBaseUrl(v);
    }
}
