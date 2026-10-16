package main.java.Recipe;

import main.java.App;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;

/**
 * CSVから食材データを読み込み管理するクラス
 * Android 版では database.csv をアプリ専用ファイル領域(filesDir)に置き、
 * 初回起動時に assets に同梱した database.csv をコピーして初期データとする
 */
public class IngredientMaster {
    /** UTF-8 の BOM(ファイル先頭に付くことがある) */
    private static final String BOM = String.valueOf((char) 0xFEFF);
    /** 食材名 → 食材。CSVの並び順を保持する */
    private final LinkedHashMap<String, Ingredient> database;

    /**
     * コンストラクタ
     * 同時にdatabase.csvを読み込む
     */
    public IngredientMaster() {
        database = new LinkedHashMap<>();
        loadCsv();
    }

    /**
     * database.csvをロードするメソッド
     */
    private void loadCsv() {
        File file = locateCsv();
        if (file == null) {
            System.err.println("[IngredientMaster] database.csv が見つかりません。食材リストは空のまま起動します。");
            return;
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            int lineNo = 0;
            String line;
            while ((line = br.readLine()) != null) {
                lineNo++;
                line = line.replace(BOM, "").trim();
                if (line.isEmpty()) continue;
                String[] data = line.split(",");
                if (data.length < 2) {
                    System.err.println("[IngredientMaster] " + file.getName() + ":" + lineNo
                            + " 列が不足しているためスキップ: " + line);
                    continue;
                }
                String catStr = data[0].trim();
                String name = data[1].trim();
                IngredientCategory cat;
                try {
                    cat = IngredientCategory.valueOf(catStr);
                } catch (IllegalArgumentException e) {
                    System.err.println("[IngredientMaster] " + file.getName() + ":" + lineNo
                            + " 未知のカテゴリ '" + catStr + "' のためスキップ");
                    continue;
                }
                // 同名の行が複数ある場合は先に出てきたものを採用する
                database.putIfAbsent(name, new Ingredient(name, cat));
            }
        } catch (IOException e) {
            System.err.println("[IngredientMaster] CSV読み込みに失敗: " + e.getMessage());
        }
    }

    /**
     * database.csv の実体ファイルを返すメソッド
     * Android ではアプリ専用ファイル領域(filesDir)固定で、無ければ assets から初期データをコピーする
     * @return CSV ファイル。初期コピーにも失敗した場合は null
     */
    private File locateCsv() {
        File f = csvFile();
        if (!f.exists()) {
            copyFromAssets(f);
        }
        return f.exists() ? f : null;
    }

    /**
     * @return filesDir/database.csv
     */
    private static File csvFile() {
        return new File(App.context().getFilesDir(), "database.csv");
    }

    /**
     * assets/database.csv を指定ファイルへコピーするメソッド(初回起動時の初期データ展開)
     * 失敗時はログに出すのみで、呼び出し側が null 判定でフォールバックする
     * @param dest コピー先ファイル
     */
    private static void copyFromAssets(File dest) {
        try (InputStream in = App.context().getAssets().open("database.csv");
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException e) {
            System.err.println("[IngredientMaster] assets からの database.csv 初期コピーに失敗: " + e.getMessage());
        }
    }

    /**
     * 指定カテゴリーに属する食材を返すメソッド
     * @param category 絞り込みたい食材カテゴリー
     * @return 該当食材のリスト
     */
    public ArrayList<Ingredient> searchIngredient(IngredientCategory category) {
        ArrayList<Ingredient> list = new ArrayList<>();
        for (Ingredient ing : database.values()) {
            if (ing.getCategory() == category) list.add(ing);
        }
        return list;
    }

    /**
     * 全ての食材を登録順で返すメソッド
     * @return 全食材のリスト
     */
    public ArrayList<Ingredient> getAllIngredients() {
        return new ArrayList<>(database.values());
    }

    /**
     * 全ての食材名を登録順で返すメソッド(AIへ渡す食材リストなどに使う)
     * @return 全食材名のリスト
     */
    public List<String> getAllIngredientNames() {
        return new ArrayList<>(database.keySet());
    }


    /**
     * メモリ上のdatabaseを引数のリストの内容で書き換え、database.csv全体を書き直す
     * @param ordered 新しい食材一覧(順序を保持)
     */
    public void replaceAll(List<Ingredient> ordered) {
        if (ordered == null) return;
        database.clear();
        for (Ingredient ing : ordered) {
            if (ing == null || ing.getName() == null || ing.getName().isEmpty()) continue;
            database.putIfAbsent(ing.getName(), ing);
        }
        saveCsv();
    }

    /**
     * database.csvに上書き保存するメソッド
     */
    private void saveCsv() {
        try {
            List<String> lines = new ArrayList<>();
            for (Ingredient ing : database.values()) {
                lines.add(ing.getCategory().name() + "," + ing.getName());
            }
            Files.write(csvFile().toPath(), lines, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[IngredientMaster] CSV保存に失敗: " + e.getMessage());
        }
    }

    /**
     * 食材を OTHER カテゴリで追加するメソッド
     * 既に同名の食材があれば追加せずにそれを返すため、「名前から食材を引く(無ければ追加)」用途にも使える
     * メモリ上のマスタ・database.csv の両方に反映される
     * @param name 食材名
     * @return 追加された(または既存の)Ingredient、空文字やnullの場合はnull
     */
    public Ingredient addIngredient(String name) {
        return addIngredient(name, IngredientCategory.OTHER);
    }

    /**
     * 食材を指定カテゴリで追加するメソッド
     * 既に同名の食材があれば追加せずにそれを返す
     * メモリ上のマスタ・database.csvの両方に反映される
     * @param name     食材名
     * @param category 食材カテゴリ
     * @return 追加された(または既存の)Ingredient。空文字/null の場合は null
     */
    public Ingredient addIngredient(String name, IngredientCategory category) {
        if (name == null) return null;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return null;
        Ingredient existing = database.get(trimmed);
        if (existing != null) return existing;
        Ingredient newIng = new Ingredient(trimmed, category);
        database.put(trimmed, newIng);
        appendToCsv(category, trimmed);
        return newIng;
    }

    /**
     * database.csv の末尾に "<CATEGORY>,<name>" を追記するメソッド
     * @param category 食材カテゴリ
     * @param name     食材名
     */
    private void appendToCsv(IngredientCategory category, String name) {
        File csv = csvFile();
        String entry = category.name() + "," + name;
        try {
            List<String> lines = csv.exists()
                    ? Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8)
                    : new ArrayList<>();
            // 既に同じ行がある場合は何もしない
            for (String l : lines) {
                if (l.replace(BOM, "").trim().equals(entry)) return;
            }
            lines.add(entry);
            Files.write(csv.toPath(), lines, StandardCharsets.UTF_8);
        } catch (Exception ignore) {
            // 読み書き失敗は無視
        }
    }
}
