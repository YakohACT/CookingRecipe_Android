package main.java.AI;

import main.java.Util.JsonUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 各AIプロバイダーの共通処理をまとめた抽象クラス
 */
public abstract class AbstractRecipeAIProvider {

    /**
     * 任意のプロンプトをモデルへ送り、モデルが返した本文(テキスト)をそのまま返す汎用呼び出し
     * @param apiKey    APIキー(ローカルLLMは未使用)
     * @param modelName モデル名
     * @param prompt    送信するプロンプト本文
     * @return モデルが返したテキスト(unescape済み。多くの場合JSON文字列)
     * @throws Exception API呼び出し/解析失敗時
     */
    public abstract String chat(String apiKey, String modelName, String prompt) throws Exception;

    /**
     * このプロバイダーで選択可能なモデル名の配列を返す
     * @return モデル名配列
     */
    public abstract String[] getAvailableModels();

    /**
     * URLを参考にレシピ案(料理名・分量付きの食材・カテゴリー)を生成する
     * レシピ用プロンプトを {@link #chat} で送り、返ったJSONを解析する。
     * 動画の添付など独自のリクエストが必要なプロバイダーはオーバーライドする
     * @param apiKey          APIキー(ローカルLLMは未使用)
     * @param modelName       モデル名
     * @param url             レシピ参照URL
     * @param ingredientNames 利用可能な食材名のリスト
     * @param categoryNames   選べるカテゴリーのラベル一覧
     * @return AIが提案したレシピ
     * @throws Exception API呼び出し/解析失敗時
     */
    public RecipeSuggestion generateRecipe(String apiKey, String modelName, String url,
                                           List<String> ingredientNames, List<String> categoryNames) throws Exception {
        return parseRecipeJson(chat(apiKey, modelName, buildPrompt(url, ingredientNames, categoryNames)));
    }

    /**
     * APIキーを使って動的にモデル一覧を取得する。
     * デフォルト実装は {@link #getAvailableModels()} の静的フォールバックを返す
     * 各プロバイダーは必要に応じてオーバーライドしてHTTPリストAPIを叩く
     * @param apiKey APIキー(ローカルLLMは未使用)
     * @return 動的取得できたモデル名配列。失敗時はフォールバック
     */
    public String[] fetchAvailableModels(String apiKey) {
        return getAvailableModels();
    }

    /**
     * 食材候補からレシピを提案させるプロンプトを構築する
     * 食材とカテゴリーの候補リストを先頭に置き、JSON形式での回答を強制
     * @param url             参照URL
     * @param ingredientNames 利用可能な食材名のリスト
     * @param categoryNames   選べるカテゴリーのラベル一覧
     * @return AIへ送信するプロンプト文字列
     */
    protected String buildPrompt(String url, List<String> ingredientNames, List<String> categoryNames) {
        String names = String.join(", ", ingredientNames);

        boolean isYoutube = YoutubeMetadataFetcher.isYoutubeUrl(url);
        // 参考情報の取得:
        //   - YouTube URL → oEmbedで動画タイトル + watchページから字幕を取得
        //   - YouTube以外 → HTMLを取得してテキスト化(WebPageFetcher)
        String pageText = "";
        if (!url.trim().isEmpty()) {
            if (isYoutube) {
                // 概要欄に材料が記載されているか判定するため食材名リストを渡す
                pageText = YoutubeMetadataFetcher.fetchSummary(url, ingredientNames);
            } else {
                pageText = WebPageFetcher.fetchSummary(url);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("あなたは日本の家庭料理のシェフです。\n\n");
        sb.append("【利用可能な食材】\n");
        sb.append(names).append("\n\n");
        sb.append("【レシピのカテゴリー候補】\n");
        sb.append(String.join(", ", categoryNames)).append("\n\n");
        if (!pageText.isEmpty()) {
            sb.append(isYoutube ? "【参考動画(指定URLから取得)】\n" : "【参考レシピページ(指定URLから取得)】\n");
            sb.append(pageText).append("\n\n");
        }
        sb.append("【ルール】\n");
        sb.append("・食材は【利用可能な食材】リストから優先して選ぶこと。同じ役割の食材がリストにあれば、必ずそちらを使うこと(例: リストに「醤油」があるのに「だし醤油」と書かない)\n");
        sb.append("・リスト内の食材だけで成立しないレシピのときに限り、リスト外の食材名を使ってよい(その場合も最小限に留める)\n");
        sb.append("・食材名は【利用可能な食材】リストの表記をそのまま使うこと(例: リストの「鶏もも」を「鶏もも肉」と書き換えない)\n");
        sb.append("・各食材の分量は、参考ページや動画に記載されている分量をそのまま使うこと。記載が無い場合は一般的な家庭料理の分量を推定すること\n");
        sb.append("・分量は amount(数値だけ。例: \"200\", \"1/2\", \"1~2\")と unit(単位だけ。例: \"g\", \"ml\", \"個\", \"本\", \"大さじ\", \"小さじ\", \"カップ\")に分けること。「大さじ3」は amount=\"3\", unit=\"大さじ\" とする\n");
        sb.append("・「適量」「少々」のように数値が無い分量は amount を空文字にし、unit に「適量」「少々」を入れること\n");
        sb.append("・categories には【レシピのカテゴリー候補】から、この料理に当てはまるものをすべて選ぶこと(例: 親子丼なら「ごはんもの」「肉料理」「卵料理」「和食」)\n");
        sb.append("・カテゴリーは候補の表記をそのまま使い、候補に無い名前を作らないこと。当てはまるものが無ければ「その他」とする\n");
        sb.append("・前置き・補足・Markdown装飾・コードフェンスは一切禁止\n");
        if (isYoutube) {
            if (!pageText.isEmpty()) {
                sb.append("・上記の動画タイトル/字幕の料理を再現するように料理名と食材を選ぶこと\n");
            } else {
                sb.append("・動画の内容を参考にすること\n");
            }
        } else if (!pageText.isEmpty()) {
            sb.append("・参考レシピページの料理を再現するように料理名と食材を選ぶこと\n");
        }
        sb.append("\n【出力形式】\n");
        sb.append("以下のキーを持つJSONオブジェクトのみを返してください。\n");
        sb.append("- title: 料理名 (文字列)\n");
        sb.append("- ingredients: 食材の配列。各要素は次のキーを持つオブジェクト\n");
        sb.append("  - name: 食材名 (文字列)。原則【利用可能な食材】リストの表記から選ぶこと\n");
        sb.append("  - amount: 数量 (文字列)\n");
        sb.append("  - unit: 単位 (文字列)\n");
        sb.append("- categories: カテゴリーの配列 (文字列の配列)。【レシピのカテゴリー候補】の表記から選ぶこと\n");
        sb.append("例: {\"title\":\"親子丼\",\"ingredients\":[{\"name\":\"鶏もも\",\"amount\":\"200\",\"unit\":\"g\"},"
                + "{\"name\":\"醤油\",\"amount\":\"2\",\"unit\":\"大さじ\"},{\"name\":\"三つ葉\",\"amount\":\"\",\"unit\":\"適量\"}],"
                + "\"categories\":[\"ごはんもの\",\"肉料理\",\"卵料理\",\"和食\"]}\n");
        return sb.toString();
    }

    /**
     * モデルが返したレシピJSON({title, ingredients[{name, amount, unit}], categories[]})を解読するメソッド
     * ingredients が食材名の文字列配列で返ってきた場合は、分量なしとして受け取る
     * categories が配列でなく1つの文字列で返ってきた場合も受け取る
     * @param text モデル本文(unescape済みのJSON文字列)
     * @return AIが提案したレシピ(検出できなかった項目は空)
     */
    protected RecipeSuggestion parseRecipeJson(String text) {
        // フォールバックを空文字にすることで、呼び出し側で「AIがレシピを検出できなかった」状態を判定できるようにする
        String title = JsonUtil.extractString(text, "title");

        List<RecipeSuggestion.Item> items = new ArrayList<>();
        for (String obj : JsonUtil.extractObjectArray(text, "ingredients")) {
            String name = JsonUtil.extractString(obj, "name");
            String cleaned = name == null ? "" : stripBrackets(name);
            if (cleaned.isEmpty()) continue;
            items.add(new RecipeSuggestion.Item(cleaned,
                    orEmpty(JsonUtil.extractScalar(obj, "amount")), orEmpty(JsonUtil.extractScalar(obj, "unit"))));
        }
        if (items.isEmpty()) {
            for (String ing : JsonUtil.extractStringArray(text, "ingredients")) {
                String cleaned = stripBrackets(ing);
                if (!cleaned.isEmpty()) items.add(new RecipeSuggestion.Item(cleaned, "", ""));
            }
        }

        List<String> labels = JsonUtil.extractStringArray(text, "categories");
        if (labels.isEmpty()) {
            String single = JsonUtil.extractString(text, "categories");
            if (single != null) labels.add(single);
        }
        List<String> categories = new ArrayList<>();
        for (String label : labels) {
            String cleaned = stripBrackets(label);
            if (!cleaned.isEmpty()) categories.add(cleaned);
        }
        return new RecipeSuggestion(title == null ? "" : stripBrackets(title), items, categories);
    }

    /**
     * null を空文字に置き換えて前後の空白を除くメソッド
     * @param s 元の文字列(null可)
     * @return 整えた文字列
     */
    private static String orEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * AIがプレースホルダや装飾を含めた場合のために角括弧・カギカッコ・先頭の箇条書き記号などを除去
     * @param s 元の文字列
     * @return 装飾を除去した文字列
     */
    private String stripBrackets(String s) {
        return s.replaceAll("[\\[\\]【】「」]", "")
                .replaceAll("^[\\s\\-*\\u30FB・]+", "")
                .trim();
    }

    /**
     * InputStream を UTF-8 文字列として読み切る
     * 各プロバイダーが個別実装していたものを共通化したヘルパー
     * @param stream 入力ストリーム(null可)
     * @return ストリーム内容の文字列(null入力時は空文字)
     * @throws IOException 読み込み失敗時
     */
    protected static String readStream(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    /**
     * URLのクエリ値として使えるよう UTF-8 でエンコードする
     * (URLEncoder.encode(String, Charset) は Android API 33 未満に無いため文字コード名で指定する)
     * @param s エンコードする文字列
     * @return エンコード済みの文字列
     */
    protected static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e); // UTF-8 は必ず使える
        }
    }

    /**
     * JSONボディをPOSTする接続を開き、ヘッダー設定とボディ送信まで済ませる。
     * レスポンスの読み取りは {@link #readOk} で行う。
     * @param url         送信先URL
     * @param body        リクエストボディJSON
     * @param connectMs   接続タイムアウト(ミリ秒)
     * @param readMs      読み取りタイムアウト(ミリ秒)
     * @param headerPairs 追加ヘッダーを「名前, 値, 名前, 値, …」の順で並べたもの
     * @return ボディ送信済みの接続
     * @throws IOException 接続/送信失敗時
     */
    protected static HttpURLConnection openJsonPost(String url, String body, int connectMs, int readMs,
                                                    String... headerPairs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        for (int i = 0; i + 1 < headerPairs.length; i += 2) {
            conn.setRequestProperty(headerPairs[i], headerPairs[i + 1]);
        }
        conn.setConnectTimeout(connectMs);
        conn.setReadTimeout(readMs);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return conn;
    }

    /**
     * HTTP 200 ならレスポンスボディを返し、それ以外はエラーボディを含めた例外を投げる。
     * @param conn       リクエスト送信済みの接続
     * @param errorLabel 例外メッセージの先頭(例: "OpenAI API エラー")
     * @param hint       例外メッセージの末尾に付ける補足(不要なら空文字)
     * @return レスポンスボディ文字列
     * @throws Exception HTTP非200時
     */
    protected static String readOk(HttpURLConnection conn, String errorLabel, String hint) throws Exception {
        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            String errorBody = readStream(conn.getErrorStream());
            throw new Exception(errorLabel + " (HTTP " + responseCode + "): " + errorBody + hint);
        }
        return readStream(conn.getInputStream());
    }

    /**
     * GETリクエストを送り、HTTP 200 ならレスポンスボディを返す。
     * モデル一覧の取得など、失敗してもフォールバックすればよい用途向け。
     * @param url         取得先URL
     * @param connectMs   接続タイムアウト(ミリ秒)
     * @param readMs      読み取りタイムアウト(ミリ秒)
     * @param headerPairs 追加ヘッダーを「名前, 値, 名前, 値, …」の順で並べたもの
     * @return レスポンスボディ。HTTP非200や通信失敗時は null
     */
    protected static String httpGetOrNull(String url, int connectMs, int readMs, String... headerPairs) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            for (int i = 0; i + 1 < headerPairs.length; i += 2) {
                conn.setRequestProperty(headerPairs[i], headerPairs[i + 1]);
            }
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            return readStream(conn.getInputStream());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * APIレスポンス全体から、モデル本文が入っている指定キーの文字列値を取り出す
     * OpenAI/Ollama/llama.cpp は "content"、Claude/Gemini は "text" フィールドに
     * モデルが返したテキストがエスケープされた文字列として入っている
     * @param jsonRes    APIレスポンス全体のJSON文字列
     * @param contentKey 抜き出す対象キー名(例: "content", "text")
     * @return モデル本文(unescape済み)
     * @throws Exception キーが見つからない/値が文字列でない場合
     */
    protected String extractContent(String jsonRes, String contentKey) throws Exception {
        String content = JsonUtil.extractString(jsonRes, contentKey);
        if (content == null) {
            throw new Exception("レスポンス解析失敗: " + contentKey + "フィールドが見つかりません。レスポンス: " + jsonRes);
        }
        return content;
    }
}
