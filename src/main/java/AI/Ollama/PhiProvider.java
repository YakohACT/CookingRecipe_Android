package main.java.AI.Ollama;

/**
 * Microsoft Phi 系モデル(Ollama 経由)
 */
public class PhiProvider extends OllamaProvider {

    /**
     * Phi 系で利用可能なモデルタグの一覧を返す。
     * @return Phi 系モデル名の配列
     */
    @Override
    public String[] getAvailableModels() {
        return new String[]{
                "phi4",
                "phi3.5",
                "phi3",
                "phi3:mini",
                "phi3:medium"
        };
    }
}
