package main.java.AI.Ollama;

/**
 * Google Gemma 系モデル(Ollama 経由)
 */
public class GemmaProvider extends OllamaProvider {

    /**
     * Gemma 系で利用可能なモデルタグの一覧を返す。
     * @return Gemma 系モデル名の配列
     */
    @Override
    public String[] getAvailableModels() {
        return new String[]{
                "gemma4:e2b",
                "gemma4:e4b"
        };
    }
}
