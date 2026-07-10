package main.java.AI.Ollama;

/**
 * Alibaba Qwen 系モデル(Ollama 経由)
 */
public class QwenProvider extends OllamaProvider {

    /**
     * Qwen / qwen-vl 系で利用可能なモデルタグの一覧を返す。
     * @return Qwen 系モデル名の配列
     */
    @Override
    public String[] getAvailableModels() {
        return new String[]{
                "qwen3.5:4b",
                "qwen3.5:9b",
                "qwen3-vl:4b",
                "qwen3-vl:8b"
        };
    }
}
