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
 * SQLite を使ってレシピを永続化するリポジトリ。
 * 3テーブル構成:
 * - recipes(id, title, url)
 * - recipe_categories(recipe_id, category)  ※ enum 名(英語)を保存
 * - recipe_ingredients(recipe_id, ingredient_name, sort_order)
 *
 * Android 版では JDBC ではなく Android 標準の {@link SQLiteDatabase} を使用する。
 * スキーマ・テーブル構成はデスクトップ版と同一のため、recipes.db はそのまま互換。
 */
public class RecipeRepository {

    private final String dbPath;
    private final IngredientMaster ingredientMaster;

    /**
     * 指定パスのSQLiteを開き、必要ならテーブルを作成する。
     * @param dbPath           SQLite データベースファイルのパス
     * @param ingredientMaster 読み込み時に食材名を解決するためのマスタ
     */
    public RecipeRepository(String dbPath, IngredientMaster ingredientMaster) {
        this.dbPath = dbPath;
        this.ingredientMaster = ingredientMaster;
        ensureSchema();
        // 実体ファイルが分かりにくいため、絶対パスをログに表示する
        System.out.println("[main.java.Recipe.RecipeRepository] SQLite DB: " + new File(dbPath).getAbsolutePath());
    }

    /**
     * recipes.db の保存先を返す。
     * Android ではアプリ専用のデータベースディレクトリ固定
     * (デスクトップ版のようなカレントディレクトリ探索は行わない)。
     * @return recipes.db の絶対パス
     */
    public static String resolveDbPath() {
        File f = App.context().getDatabasePath("recipes.db");
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        return f.getAbsolutePath();
    }

    /**
     * SQLite データベースを開く(存在しなければ作成)。
     * @return 開いた状態のデータベース(呼び出し側で close すること)
     */
    private SQLiteDatabase connect() {
        return SQLiteDatabase.openOrCreateDatabase(dbPath, null);
    }

    /**
     * 必要な3テーブル(recipes / recipe_categories / recipe_ingredients)を冪等に作成する。
     */
    private void ensureSchema() {
        try (SQLiteDatabase db = connect()) {
            db.execSQL("PRAGMA foreign_keys = ON");
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
                    "  PRIMARY KEY (recipe_id, sort_order)," +
                    "  FOREIGN KEY (recipe_id) REFERENCES recipes(id) ON DELETE CASCADE" +
                    ")");
        } catch (Exception e) {
            throw new RuntimeException("SQLiteの初期化に失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * 全レシピを読み込む。カテゴリーと食材は別テーブルから結合して復元する。
     * @return 全レシピのリスト(順序は recipes テーブルの id 昇順)
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
                    "SELECT recipe_id, ingredient_name FROM recipe_ingredients ORDER BY recipe_id, sort_order", null)) {
                while (c.moveToNext()) {
                    RecipeBuilder b = builders.get(c.getLong(0));
                    if (b == null) continue;
                    b.ingredients.add(lookupIngredient(c.getString(1)));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("レシピの読み込みに失敗: " + e.getMessage(), e);
        }

        List<Recipe> result = new ArrayList<>();
        for (RecipeBuilder b : builders.values()) {
            EnumSet<RecipeCategory> cats = b.categories.isEmpty()
                    ? EnumSet.of(RecipeCategory.OTHER) : b.categories;
            Recipe r = new Recipe(b.title, b.url, b.ingredients, cats);
            r.setId(b.id);
            result.add(r);
        }
        return result;
    }

    /**
     * レシピを新規挿入し、生成された id を返す。
     * カテゴリー・食材は1トランザクション内で関連テーブルにも挿入する。
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

                insertRelations(db, id, recipe);

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
     * 既存レシピを更新する。タイトル/URLは UPDATE、関連テーブル(カテゴリー・食材)は
     * 全削除してから再挿入することで内容差分を反映する。1トランザクションで原子的に行う。
     * @param recipe 同じ id を持つ更新後のレシピ
     * @throws IllegalArgumentException recipe.getId() が 0 以下のとき
     */
    public void update(Recipe recipe) {
        long id = recipe.getId();
        if (id <= 0) {
            throw new IllegalArgumentException("main.java.Recipe.Recipe has no id; use insert() instead");
        }
        try (SQLiteDatabase db = connect()) {
            db.beginTransaction();
            try {
                ContentValues cv = new ContentValues();
                cv.put("title", recipe.getTitle());
                cv.put("url", recipe.getUrl());
                db.update("recipes", cv, "id = ?", new String[]{String.valueOf(id)});

                db.delete("recipe_categories", "recipe_id = ?", new String[]{String.valueOf(id)});
                db.delete("recipe_ingredients", "recipe_id = ?", new String[]{String.valueOf(id)});
                insertRelations(db, id, recipe);

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception e) {
            throw new RuntimeException("レシピ更新失敗: " + e.getMessage(), e);
        }
    }

    /**
     * 指定 id のレシピを削除する。関連テーブルも明示的に消す
     * (foreign_keys プラグマが接続毎の設定のため CASCADE に頼らない)。
     * @param id 削除対象レシピのDB主キー
     */
    public void delete(long id) {
        try (SQLiteDatabase db = connect()) {
            db.beginTransaction();
            try {
                db.delete("recipe_categories", "recipe_id = ?", new String[]{String.valueOf(id)});
                db.delete("recipe_ingredients", "recipe_id = ?", new String[]{String.valueOf(id)});
                db.delete("recipes", "id = ?", new String[]{String.valueOf(id)});
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception e) {
            throw new RuntimeException("レシピの削除に失敗: " + e.getMessage(), e);
        }
    }

    /**
     * カテゴリー・食材の関連テーブルへ1レシピ分を挿入する(insert / update 共通処理)。
     * @param db     トランザクション中のデータベース
     * @param id     レシピの主キー
     * @param recipe 挿入するレシピ
     */
    private static void insertRelations(SQLiteDatabase db, long id, Recipe recipe) {
        for (RecipeCategory cat : recipe.getCategories()) {
            ContentValues cv = new ContentValues();
            cv.put("recipe_id", id);
            cv.put("category", cat.name());
            db.insertOrThrow("recipe_categories", null, cv);
        }
        int order = 0;
        for (Ingredient ing : recipe.getIngredients()) {
            ContentValues cv = new ContentValues();
            cv.put("recipe_id", id);
            cv.put("ingredient_name", ing.getName());
            cv.put("sort_order", order++);
            db.insertOrThrow("recipe_ingredients", null, cv);
        }
    }

    /**
     * 食材名から {@link IngredientMaster} を引いて {@link Ingredient} を返す。
     * マスタに存在しない場合は OTHER カテゴリで自動的にマスタ + database.csv に追加し、
     * その Ingredient を返す。
     * @param name 食材名
     * @return 解決された Ingredient
     */
    private Ingredient lookupIngredient(String name) {
        for (Ingredient ing : ingredientMaster.getAllIngredients()) {
            if (ing.getName().equals(name)) return ing;
        }
        // マスタに無い場合は OTHER で自動追加(database.csv にも書き込む)
        Ingredient added = ingredientMaster.addIngredient(name);
        return (added != null) ? added : new Ingredient(name, IngredientCategory.OTHER);
    }

    /** 読み込み中の中間状態を保持する内部Builder */
    private static class RecipeBuilder {
        final long id;
        final String title;
        final String url;
        final ArrayList<Ingredient> ingredients = new ArrayList<>();
        final EnumSet<RecipeCategory> categories = EnumSet.noneOf(RecipeCategory.class);

        /**
         * @param id    DB主キー
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
