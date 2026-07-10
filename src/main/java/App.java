package main.java;

import android.app.Application;
import android.content.Context;

/**
 * アプリケーションクラス。
 * UI を持たない層(RecipeRepository / IngredientMaster / SecureKeyStore など)から
 * ファイル保存先やアセットへアクセスするための Context を静的に提供する。
 */
public class App extends Application {

    private static Context context;

    @Override
    public void onCreate() {
        super.onCreate();
        context = getApplicationContext();
    }

    /**
     * @return アプリケーション Context
     */
    public static Context context() {
        return context;
    }
}
