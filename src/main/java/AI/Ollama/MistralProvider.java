package main.java.AI.Ollama;

/**
 * Mistral AI 系モデル(Ollama 経由)
 */
public class MistralProvider extends OllamaProvider {

    /**
     * Mistral / Mixtral 系で利用可能なモデルタグの一覧を返す。
     * @return Mistral 系モデル名の配列
     */
    @Override
    public String[] getAvailableModels() {
        return new String[]{
                "mistral",
                "mistral:7b",
                "mistral-nemo",
                "mistral-small",
                "mixtral"
        };
    }
}
