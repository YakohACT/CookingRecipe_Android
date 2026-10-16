package main.java.Recipe;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import main.java.App;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLiteを使ってレシピ保存するクラス
 * テーブル構成:
 * - recipes(id, title, url)
 * - recipe_categories(recipe_id, category)  ※ enum 名(英語)を保存
 * - recipe_ingredients(recipe_id, ingredient_name, sort_order, amount, unit)
 *
 * Android 版では sqlite-jdbc ではなく Android 標準の {@link SQLiteDatabase} を使う。
 * スキーマはデスクトップ版と同一のため、recipes.db はそのまま互換
 */
public class RecipeRepository {

    private final String dbPath;
    private final IngredientMaster ingredientMaster;

    /**
     * 指定パスのSQLiteを開き、必要ならテーブルを作成するメソッド
     * @param dbPath           SQLite データベースファイルのパス
     * @param ingredientMaster 読み込み時に食材名を解決するためのマスタ
     */
    public RecipeRepository(String dbPath, IngredientMaster ingredientMaster) {
        this.dbPath = dbPath;
        this.ingredientMaster = ingredientMaster;
        ensureSchema();
        // 実体ファイルが分かりにくいため、絶対パスをログに表示する
        System.out.println("[RecipeRepository] SQLite DB: " + new File(dbPath).getAbsolutePath());
    }

    /**
     * recipes.db の保存先を返すメソッド
     * Android ではアプリ専用のデータベースディレクトリ固定
     * @return recipes.db の絶対パス
     */
    public static String resolveDbPath() {
        File f = App.context().getDatabasePath("recipes.db");
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        return f.getAbsolutePath();
    }

    /**
     * SQLite を開くメソッド(存在しなければ作成する)
     * SQLite の外部キー制約(ON DELETE CASCADE)は接続ごとに有効化が必要なため、毎回 ON で開く
     * @return 開いた状態のデータベース(呼び出し側で close すること)
     */
    private SQLiteDatabase connect() {
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dbPath, null);
        db.setForeignKeyConstraintsEnabled(true);
        return db;
    }

    /**
     * 必要な3テーブル(recipes / recipe_categories / recipe_ingredients)を作成するメソッド
     * 分量の列(amount / unit)が無い古いDBには列を追加する
     */
    private void ensureSchema() {
        try (SQLiteDatabase db = connect()) {
            db.execSQL(
                    "CREATE TABLE IF NOT EXISTS recipes (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  title TEXT NOT NULL," +
                    "  url TEXT NOT NULL" +
                    ")");
            db.execSQL(
                    "CREATE TABLE IF NOT EXISTS recipe_categories (" +
                    "  recipe_id INTEGER NOT NULL," +
                    "  category  TEXT NOT NULL," +
                    "  PRIMARY KEY (recipe_id, category)," +
                    "  FOREIGN KEY (recipe_id) REFERENCES recipes(id) ON DELETE CASCADE" +
                    ")");
            db.execSQL(
                    "CREATE TABLE IF NOT EXISTS recipe_ingredients (" +
                    "  recipe_id       INTEGER NOT NULL," +
                    "  ingredient_name TEXT NOT NULL," +
                    "  sort_order      INTEGER NOT NULL," +
                    "  amount          TEXT NOT NULL DEFAULT ''," +
                    "  unit            TEXT NOT NULL DEFAULT ''," +
                    "  PRIMARY KEY (recipe_id, sort_order)," +
                    "  FOREIGN KEY (recipe_id) REFERENCES recipes(id) ON DELETE CASCADE" +
                    ")");
            addQuantityColumnsIfMissing(db);
        } catch (Exception e) {
            throw new RuntimeException("SQLiteの初期化に失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * 分量の列が追加される前に作られた recipe_ingredients に amount / unit 列を追加するメソッド
     * 既存の行は分量なし(空文字)になる
     * @param db データベース
     */
    private void addQuantityColumnsIfMissing(SQLiteDatabase db) {
        boolean hasAmount = false;
        boolean hasUnit = false;
        try (Cursor c = db.rawQuery("PRAGMA table_info(recipe_ingredients)", null)) {
            int nameCol = c.getColumnIndexOrThrow("name");
            while (c.moveToNext()) {
                String column = c.getString(nameCol);
                if ("amount".equals(column)) hasAmount = true;
                if ("unit".equals(column)) hasUnit = true;
            }
        }
        if (!hasAmount) db.execSQL("ALTER TABLE recipe_ingredients ADD COLUMN amount TEXT NOT NULL DEFAULT ''");
        if (!hasUnit) db.execSQL("ALTER TABLE recipe_ingredients ADD COLUMN unit TEXT NOT NULL DEFAULT ''");
    }

    /**
     * 全レシピを読み込む。カテゴリーと食材は別テーブルから結合して復元するメソッド
     * @return 全レシピのリスト
     */
    public List<Recipe> findAll() {
        Map<Long, RecipeBuilder> builders = new LinkedHashMap<>();
        try (SQLiteDatabase db = connect()) {
            try (Cursor c = db.rawQuery("SELECT id, title, url FROM recipes ORDER BY id", null)) {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    builders.put(id, new RecipeBuilder(id, c.getString(1), c.getString(2)));
                }
            }

            try (Cursor c = db.rawQuery("SELECT recipe_id, category FROM recipe_categories", null)) {
                while (c.moveToNext()) {
                    RecipeBuilder b = builders.get(c.getLong(0));
                    if (b == null) continue;
                    try {
                        b.categories.add(RecipeCategory.valueOf(c.getString(1)));
                    } catch (IllegalArgumentException ignore) {
                        // 旧バージョンの enum 名が残っていた場合は無視
                    }
                }
            }

            try (Cursor c = db.rawQuery(
                    "SELECT recipe_id, ingredient_name, amount, unit FROM recipe_ingredients ORDER BY recipe_id, sort_order", null)) {
                while (c.moveToNext()) {
                    RecipeBuilder b = builders.get(c.getLong(0));
                    if (b == null) continue;
                    // マスタに無い食材名は OTHER で自動追加(database.csv にも書き込む)
                    Ingredient ing = ingredientMaster.addIngredient(c.getString(1));
                    if (ing == null) continue;
                    b.ingredients.add(ing);
                    b.quantities.put(ing.getName(), Quantity.of(c.getString(2), c.getString(3)));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("レシピの読み込みに失敗: " + e.getMessage(), e);
        }

        List<Recipe> result = new ArrayList<>();
        for (RecipeBuilder b : builders.values()) {
            // カテゴリーが空の場合は Recipe 側で OTHER になる
            Recipe r = new Recipe(b.title, b.url, b.ingredients, b.quantities, b.categories);
            r.setId(b.id);
            result.add(r);
        }
        return result;
    }

    /**
     * レシピを新規挿入し、生成された id を返すメソッド
     * @param recipe 保存するレシピ(id は無視される)
     * @return 自動採番された id
     */
    public long insert(Recipe recipe) {
        try (SQLiteDatabase db = connect()) {
            db.beginTransaction();
            try {
                ContentValues cv = new ContentValues();
                cv.put("title", recipe.getTitle());
                cv.put("url", recipe.getUrl());
                long id = db.insertOrThrow("recipes", null, cv);

                insertChildren(db, id, recipe);
                db.setTransactionSuccessful();
                return id;
            } finally {
                db.endTransaction();
            }
        } catch (Exception e) {
            throw new RuntimeException("レシピの保存に失敗: " + e.getMessage(), e);
        }
    }

    /**
     * 既存レシピを更新するメソッド
     * @param recipe 同じ id を持つ更新後のレシピ
     * @throws IllegalArgumentException recipe.getId()が0以下のとき
     */
    public void update(Recipe recipe) {
        long id = recipe.getId();
        if (id <= 0) {
            throw new IllegalArgumentException("Recipe has no id; use insert() instead");
        }
        String[] idArg = {String.valueOf(id)};
        try (SQLiteDatabase db = connect()) {
            db.beginTransaction();
            try {
                ContentValues cv = new ContentValues();
                cv.put("title", recipe.getTitle());
                cv.put("url", recipe.getUrl());
                db.update("recipes", cv, "id = ?", idArg);

                // カテゴリーと食材は一旦消してから入れ直す
                db.delete("recipe_categories", "recipe_id = ?", idArg);
                db.delete("recipe_ingredients", "recipe_id = ?", idArg);
                insertChildren(db, id, recipe);

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception e) {
            throw new RuntimeException("レシピ更新失敗: " + e.getMessage(), e);
        }
    }

    /**
     * レシピのカテゴリーと食材を子テーブルへ挿入するメソッド(insert / update 共通)
     * @param db     トランザクション中のデータベース
     * @param id     親レシピのDBキー
     * @param recipe 保存するレシピ
     */
    private void insertChildren(SQLiteDatabase db, long id, Recipe recipe) {
        for (RecipeCategory cat : recipe.getCategories()) {
            ContentValues cv = new ContentValues();
            cv.put("recipe_id", id);
            cv.put("category", cat.name());
            db.insertOrThrow("recipe_categories", null, cv);
        }

        int order = 0;
        for (Ingredient ing : recipe.getIngredients()) {
            Quantity q = recipe.getQuantity(ing);
            ContentValues cv = new ContentValues();
            cv.put("recipe_id", id);
            cv.put("ingredient_name", ing.getName());
            cv.put("sort_order", order++);
            cv.put("amount", q.getAmount());
            cv.put("unit", q.getUnit());
            db.insertOrThrow("recipe_ingredients", null, cv);
        }
    }

    /**
     * 指定 id のレシピを削除するメソッド
     * recipe_categories / recipe_ingredients の行は ON DELETE CASCADE で一緒に削除される
     * @param id 削除対象レシピのDBキー
     */
    public void delete(long id) {
        try (SQLiteDatabase db = connect()) {
            db.delete("recipes", "id = ?", new String[]{String.valueOf(id)});
        } catch (Exception e) {
            throw new RuntimeException("レシピの削除に失敗: " + e.getMessage(), e);
        }
    }

    /** 読み込み中の中間状態を保持する内部Builder */
    private static class RecipeBuilder {
        final long id;
        final String title;
        final String url;
        final ArrayList<Ingredient> ingredients = new ArrayList<>();
        final Map<String, Quantity> quantities = new LinkedHashMap<>();
        final EnumSet<RecipeCategory> categories = EnumSet.noneOf(RecipeCategory.class);

        /**
         * @param id    DBのIDキー
         * @param title レシピ名
         * @param url   参照URL
         */
        RecipeBuilder(long id, String title, String url) {
            this.id = id;
            this.title = title;
            this.url = url;
        }
    }
}
