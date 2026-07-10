package main.java.AI;

import main.java.AI.Ollama.*;
import main.java.Recipe.Ingredient;
import main.java.Recipe.IngredientNormalizer;

import java.util.ArrayList;
import java.util.List;

/**
 * ユーザー設定を保持し、各AIプロバイダーへ処理を委譲する橋渡しクラス
 */
public class RecipeAIService {

    public enum Provider { OPENAI, GEMINI, CLAUDE, OLLAMA }

    private Provider selectedProvider = Provider.OPENAI;
    private String apiKey = "";
    private String modelName = "";

    /**
     * 利用するプロバイダー・APIキー・モデル名を一括設定する。
     * @param provider  AIプロバイダー
     * @param apiKey    APIキー(Ollamaは空文字でよい)
     * @param modelName 利用モデル名
     */
    public void setConfig(Provider provider, String apiKey, String modelName) {
        this.selectedProvider = provider;
        this.apiKey = apiKey;
        this.modelName = modelName;
    }

    /**
     * 設定済みプロバイダーへレシピ提案を依頼する。
     * Ollama 以外で APIキー未設定の場合は例外を投げる。
     * @param url            参照URL
     * @param allIngredients 利用可能な食材リスト
     * @return [タイトル, "食材1,食材2,…"] の2要素配列
     * @throws Exception API呼び出し失敗時 / APIキー未設定時
     */
    public String[] suggestRecipe(String url, ArrayList<Ingredient> allIngredients) throws Exception {
        // OllamaはローカルLLMなのでAPIキー不要
        if (selectedProvider != Provider.OLLAMA && (apiKey == null || apiKey.isEmpty())) {
            throw new Exception("APIキーが設定されていない");
        }

        AbstractRecipeAIProvider aiProvider = createProvider(selectedProvider, modelName);

        // modelName も一緒に渡す
        return aiProvider.generateRecipe(apiKey, modelName, url, allIngredients);
    }

    /**
     * 設定済みプロバイダーに、食材名の表記ゆれグループを検出させる。
     * Ollama 以外で APIキー未設定の場合は例外を投げる。
     * @param ingredientNames 現在登録されている全食材名
     * @return 表記ゆれグループのリスト(表記ゆれが無ければ空)
     * @throws Exception API呼び出し失敗時 / APIキー未設定時
     */
    public List<IngredientNormalizer.VariantGroup> detectIngredientVariants(List<String> ingredientNames) throws Exception {
        if (selectedProvider != Provider.OLLAMA && (apiKey == null || apiKey.isEmpty())) {
            throw new Exception("APIキーが設定されていない");
        }
        AbstractRecipeAIProvider aiProvider = createProvider(selectedProvider, modelName);
        String prompt = IngredientNormalizer.buildPrompt(ingredientNames);
        String json = aiProvider.chat(apiKey, modelName, prompt);
        return IngredientNormalizer.parseGroups(json);
    }

    /**
     * 指定されたプロバイダーの利用可能なモデル一覧を取得する。
     * Ollama の場合は全サブクラスのモデルを集約して返す
     * @param provider 対象のAIプロバイダー
     * @return モデル名の配列
     */
    public String[] getModelsForProvider(Provider provider) {
        if (provider == Provider.OLLAMA) {
            ArrayList<String> all = new ArrayList<>();
            for (OllamaProvider p : ollamaSubclasses()) {
                for (String m : p.getAvailableModels()) all.add(m);
            }
            return all.toArray(new String[0]);
        }
        return createProvider(provider, "").getAvailableModels();
    }

    /**
     * 動的にプロバイダのAPIへ問い合わせて利用可能なモデル一覧を取得する。
     * Ollama は localhost:11434/api/tags、それ以外はプロバイダの models エンドポイントを叩く。
     * 失敗時は各プロバイダの静的フォールバックが返る(例外は呼び出し側へ漏れない)。
     * @param provider 対象プロバイダー
     * @param apiKey   APIキー(Ollamaは未使用、null/空文字許容)
     * @return モデル名配列
     */
    public String[] fetchModelsForProvider(Provider provider, String apiKey) {
        if (provider == Provider.OLLAMA) {
            // Ollamaは1回 /api/tags を叩けば全モデルが取れるのでサブクラス集約は不要
            return new LlamaProvider().fetchAvailableModels(apiKey);
        }
        return createProvider(provider, "").fetchAvailableModels(apiKey);
    }

    /**
     * プロバイダー(と Ollama の場合は modelName)から具象クラスを生成する。
     * @param provider  AIプロバイダー
     * @param modelName Ollama の場合のサブクラス振り分けに使うモデル名
     * @return 対応する具象 AbstractRecipeAIProvider
     */
    private static AbstractRecipeAIProvider createProvider(Provider provider, String modelName) {
        switch (provider) {
            case GEMINI: return new GeminiProvider();
            case CLAUDE: return new ClaudeProvider();
            case OLLAMA: return ollamaProviderForModel(modelName);
            case OPENAI: default: return new OpenAIProvider();
        }
    }

    /**
     * モデル名のプレフィックスで Ollama サブクラスを振り分ける。
     * 不明なモデル名は {@link LlamaProvider} をフォールバックとして利用(API挙動は同じ)。
     * @param modelName Ollamaモデル名(例: "gemma4:e2b")
     * @return 対応する OllamaProvider サブクラスのインスタンス
     */
    private static OllamaProvider ollamaProviderForModel(String modelName) {
        String lower = (modelName == null) ? "" : modelName.toLowerCase();
        if (lower.startsWith("gemma"))                                  return new GemmaProvider();
        if (lower.startsWith("mistral") || lower.startsWith("mixtral")) return new MistralProvider();
        if (lower.startsWith("phi"))                                    return new PhiProvider();
        if (lower.startsWith("qwen") || lower.startsWith("qwq")) return new QwenProvider();
        return new LlamaProvider();
    }

    /**
     * Ollama 系サブクラスの一覧(モデル列挙用)。
     * @return 全サブクラスインスタンスの配列
     */
    private static OllamaProvider[] ollamaSubclasses() {
        return new OllamaProvider[]{
                new LlamaProvider(),
                new GemmaProvider(),
                new MistralProvider(),
                new PhiProvider(),
                new QwenProvider()
        };
    }
}
