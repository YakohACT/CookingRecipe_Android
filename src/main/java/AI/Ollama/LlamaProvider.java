package main.java.AI.Ollama;

/**
 * Meta Llama 系モデル(Ollama 経由)
 */
public class LlamaProvider extends OllamaProvider {

    /**
     * Llama 系で利用可能なモデルタグの一覧を返す。
     * @return Llama 系モデル名の配列
     */
    @Override
    public String[] getAvailableModels() {
        return new String[]{
                "llama3:8b"
        };
    }
}
