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
 * CSVから食材データを読み込み管理するクラス。
 * LinkedHashMap を使うことで、CSVに記述された順序のまま取り出せる
 */
public class IngredientMaster {
    private LinkedHashMap<Ingredient, String> database;

    /**
     * インスタンス生成と同時に database.csv を読み込む。
     * CSV が見つからない場合は database が空のまま生成される。
     */
    public IngredientMaster() {
        database = new LinkedHashMap<>();
        loadCsv();
    }

    /**
     * database.csv からのデータ読み込み。
     * 実行ディレクトリ依存の相対パスをまず試し、それでも見つからない場合は
     * 自身のJAR(またはクラスファイル)と同階層・1つ上の階層も探す。
     * jpackage で配布された .exe からの起動でも見つかるようになっている
     */
    private void loadCsv() {
        File file = locateCsv();
        if (file == null) {
            System.err.println("[IngredientMaster] database.csv \u304C\u898B\u3064\u304B\u308A\u307E\u305B\u3093\u3002\u98DF\u6750\u30EA\u30B9\u30C8\u306F\u7A7A\u306E\u307E\u307E\u8D77\u52D5\u3057\u307E\u3059\u3002");
            return;
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            int lineNo = 0;
            String line;
            while ((line = br.readLine()) != null) {
                lineNo++;
                line = line.replace("\uFEFF", "").trim();
                if (line.isEmpty()) continue;
                String[] data = line.split(",");
                if (data.length < 2) {
                    System.err.println("[IngredientMaster] " + file.getName() + ":" + lineNo
                            + " \u5217\u304C\u4E0D\u8DB3\u3057\u3066\u3044\u308B\u305F\u3081\u30B9\u30AD\u30C3\u30D7: " + line);
                    continue;
                }
                String catStr = data[0].trim();
                String name = data[1].trim();
                IngredientCategory cat;
                try {
                    cat = IngredientCategory.valueOf(catStr);
                } catch (IllegalArgumentException e) {
                    System.err.println("[IngredientMaster] " + file.getName() + ":" + lineNo
                            + " \u672A\u77E5\u306E\u30AB\u30C6\u30B4\u30EA '" + catStr + "' \u306E\u305F\u3081\u30B9\u30AD\u30C3\u30D7");
                    continue;
                }
                database.put(new Ingredient(name, cat), name);
            }
        } catch (IOException e) {
            System.err.println("[IngredientMaster] CSV\u8AAD\u307F\u8FBC\u307F\u306B\u5931\u6557: " + e.getMessage());
        }
    }

    /**
     * database.csv の実体ファイルを返す。
     * Android ではアプリ専用ファイル領域(filesDir)固定で、
     * 初回起動時に assets に同梱した database.csv をコピーして初期データとする。
     * @return CSV ファイル。初期コピーにも失敗した場合は null
     */
    private File locateCsv() {
        File f = new File(App.context().getFilesDir(), "database.csv");
        if (!f.exists()) {
            copyFromAssets(f);
        }
        return f.exists() ? f : null;
    }

    /**
     * assets/database.csv を指定ファイルへコピーする(初回起動時の初期データ展開)。
     * 失敗時はログに出すのみで、呼び出し側が null 判定でフォールバックする。
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
     * 指定カテゴリーに属する食材を CSV 順で返す。
     * @param category 絞り込みたい食材カテゴリー
     * @return 該当食材のリスト(マッチが無い場合は空のリスト)
     */
    public ArrayList<Ingredient> searchIngredient(IngredientCategory category) {
        ArrayList<Ingredient> list = new ArrayList<>();
        for (Ingredient ing : database.keySet()) {
            if (ing.getCategory() == category) list.add(ing);
        }
        return list;
    }

    /**
     * 全食材を CSV に記述された順序で返す。
     * @return 全食材のリスト
     */
    public ArrayList<Ingredient> getAllIngredients() {
        return new ArrayList<>(database.keySet());
    }

    /**
     * 食材一覧を一括置換する(順序・カテゴリの変更を反映)。
     * メモリ上の database を引数のリストの内容で書き換え、database.csv 全体を書き直す。
     * 順序はリストの順序がそのまま使われる。
     * @param ordered 新しい食材一覧(順序を保持)
     */
    public void replaceAll(List<Ingredient> ordered) {
        if (ordered == null) return;
        database.clear();
        for (Ingredient ing : ordered) {
            if (ing == null || ing.getName() == null || ing.getName().isEmpty()) continue;
            database.put(ing, ing.getName());
        }
        saveCsv();
    }

    /**
     * 現在のメモリ上の食材一覧を database.csv に上書き保存する。
     */
    private void saveCsv() {
        File csv = locateCsv();
        if (csv == null) {
            csv = new File(App.context().getFilesDir(), "database.csv");
        }
        try {
            List<String> lines = new ArrayList<>();
            for (Ingredient ing : database.keySet()) {
                lines.add(ing.getCategory().name() + "," + ing.getName());
            }
            Files.write(csv.toPath(), lines, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[IngredientMaster] CSV保存に失敗: " + e.getMessage());
        }
    }

    /**
     * 食材を OTHER カテゴリで追加する(同名の食材が既にあればそれを返す)。
     * メモリ上のマスタ・database.csv の両方に反映される。
     * @param name 食材名
     * @return 追加された(または既存の)Ingredient。空文字/null の場合は null
     */
    public Ingredient addIngredient(String name) {
        return addIngredient(name, IngredientCategory.OTHER);
    }

    /**
     * 食材を指定カテゴリで追加する(同名の食材が既にあればそれを返す)。
     * メモリ上のマスタ・database.csv の両方に反映される。
     * @param name     食材名
     * @param category 食材カテゴリ
     * @return 追加された(または既存の)Ingredient。空文字/null の場合は null
     */
    public Ingredient addIngredient(String name, IngredientCategory category) {
        if (name == null) return null;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return null;
        // 既に同名の食材があればそれを返す(equalsは名前一致)
        for (Ingredient existing : database.keySet()) {
            if (existing.getName().equals(trimmed)) return existing;
        }
        Ingredient newIng = new Ingredient(trimmed, category);
        database.put(newIng, trimmed);
        appendToCsv(category, trimmed);
        return newIng;
    }

    /**
     * database.csv の末尾に "<CATEGORY>,<name>" を追記する。
     * 既に同じ行があればスキップする。失敗時は黙殺する(マスタ追加自体は in-memory に反映済み)。
     * @param category 食材カテゴリ
     * @param name     食材名
     */
    private void appendToCsv(IngredientCategory category, String name) {
        File csv = locateCsv();
        if (csv == null) {
            // 既存のCSVが見つからない場合はアプリ専用ファイル領域に新規作成する
            csv = new File(App.context().getFilesDir(), "database.csv");
        }
        String entry = category.name() + "," + name;
        try {
            List<String> lines = csv.exists()
                    ? Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8)
                    : new ArrayList<>();
            // 既に同じ行がある場合は何もしない(冪等)。BOM混入も考慮
            for (String l : lines) {
                if (l.replace("﻿", "").trim().equals(entry)) return;
            }
            lines.add(entry);
            Files.write(csv.toPath(), lines, StandardCharsets.UTF_8);
        } catch (Exception ignore) {
            // 読み書き失敗は無視。in-memoryの追加は既に成功している
        }
    }
}