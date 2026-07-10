package main.java.UI;

import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import main.java.MainActivity;
import main.java.AI.Ollama.OllamaProvider;
import main.java.AI.RecipeAIService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * AI利用設定画面パネル。
 * APIキーは OpenAI / Gemini / Claude / Ollama それぞれで個別に保存され、
 * プロバイダーを切替えると該当プロバイダーの保存済みキーが復元される。
 *
 * モデル一覧は次のタイミングで API から動的取得する:
 *   - プロバイダー切替時
 *   - 「更新」ボタン押下時
 * 取得失敗時は各プロバイダーの静的フォールバックリストにフォールバックする。
 *
 * Android 追加要素: Ollama 選択時は APIキー欄の代わりに
 * Ollama サーバーURL(例: 同一LAN上のPC)の入力欄を表示する。
 */
public class SettingsPanel extends ScrollView {

    private final MainActivity owner;
    private final Spinner providerSpinner;
    private final Spinner modelSpinner;
    private final Button refreshBtn;
    private final EditText keyField;
    private final LinearLayout keySection;
    private final LinearLayout ollamaSection;
    private final EditText ollamaUrlField;

    /** 現在モデルSpinnerに表示中のモデル一覧 */
    private List<String> currentModels = new ArrayList<>();

    /**
     * AI設定パネルを構築する。プロバイダー切替で該当プロバイダーの保存済みモデル/キーを復元し、
     * モデル一覧は API から非同期で動的取得する。
     * @param owner 共有状態(プロバイダー、モデル、APIキー等)を保持するアクティビティ
     */
    public SettingsPanel(MainActivity owner) {
        super(owner);
        this.owner = owner;

        LinearLayout form = new LinearLayout(owner);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = UIComponents.dp(owner, 16);
        form.setPadding(pad, pad, pad, pad);
        addView(form, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        form.addView(UIComponents.createTitleLabel(owner, "AI利用設定"));
        addSpacer(form, 16);

        form.addView(UIComponents.createLabel(owner, "利用するAIプロバイダー:"));
        providerSpinner = new Spinner(owner);
        providerSpinner.setAdapter(UIComponents.createSpinnerAdapter(owner, RecipeAIService.Provider.values()));
        providerSpinner.setSelection(indexOfProvider(owner.getCurrentAiProvider()));
        form.addView(providerSpinner);
        addSpacer(form, 12);

        form.addView(UIComponents.createLabel(owner, "利用するモデル:"));
        LinearLayout modelRow = new LinearLayout(owner);
        modelRow.setOrientation(LinearLayout.HORIZONTAL);
        modelSpinner = new Spinner(owner);
        modelRow.addView(modelSpinner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        refreshBtn = new Button(owner);
        refreshBtn.setText("更新");
        refreshBtn.setAllCaps(false);
        modelRow.addView(refreshBtn);
        form.addView(modelRow);

        // ===== APIキー入力欄 (Ollama選択時は非表示) =====
        keySection = new LinearLayout(owner);
        keySection.setOrientation(LinearLayout.VERTICAL);
        addSpacer(keySection, 12);
        keySection.addView(UIComponents.createLabel(owner, "APIキー (プロバイダーごとに別々に保存されます):"));
        keyField = new EditText(owner);
        keyField.setSingleLine(true);
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keySection.addView(keyField, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        form.addView(keySection);

        // ===== OllamaサーバーURL入力欄 (Ollama選択時のみ表示) =====
        ollamaSection = new LinearLayout(owner);
        ollamaSection.setOrientation(LinearLayout.VERTICAL);
        addSpacer(ollamaSection, 12);
        ollamaSection.addView(UIComponents.createLabel(owner,
                "OllamaサーバーURL (例: http://192.168.1.10:11434):"));
        TextView hint = UIComponents.createLabel(owner,
                "Android 端末内では Ollama は動作しないため、同一ネットワーク上のPCで\n"
                + "`ollama serve` を起動し、そのPCのアドレスを指定してください。\n"
                + "空欄の場合は " + OllamaProvider.DEFAULT_BASE_URL + " に接続します。");
        hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
        hint.setTextColor(0xFF888888);
        ollamaSection.addView(hint);
        ollamaUrlField = new EditText(owner);
        ollamaUrlField.setSingleLine(true);
        ollamaUrlField.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        ollamaUrlField.setText(owner.getOllamaUrl());
        ollamaSection.addView(ollamaUrlField, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        form.addView(ollamaSection);

        addSpacer(form, 24);
        Button btnSave = UIComponents.createPrimaryButton(owner, "設定を保存");
        form.addView(btnSave);

        // ===== イベント =====
        providerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                RecipeAIService.Provider p = (RecipeAIService.Provider) parent.getItemAtPosition(position);
                keyField.setText(p == null ? "" : owner.getApiKeyFor(p));
                applyKeyVisibility();
                populateModels();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        refreshBtn.setOnClickListener(v -> populateModels());

        btnSave.setOnClickListener(v -> {
            RecipeAIService.Provider selected = selectedProvider();
            owner.setCurrentAiProvider(selected);
            Object model = modelSpinner.getSelectedItem();
            owner.setModelFor(selected, model == null ? "" : model.toString());
            if (selected == RecipeAIService.Provider.OLLAMA) {
                // Ollama はキー不要。代わりに接続先URLを保存
                owner.setOllamaUrl(ollamaUrlField.getText().toString().trim());
            } else {
                owner.setApiKeyFor(selected, keyField.getText().toString().trim());
            }
            UIComponents.info(owner, "設定を保存しました");
            owner.showWelcome();
        });

        // 初期表示
        RecipeAIService.Provider initial = owner.getCurrentAiProvider();
        keyField.setText(owner.getApiKeyFor(initial));
        applyKeyVisibility();
        populateModels();
    }

    /**
     * 縦方向スペーサを追加する。
     * @param parent   追加先
     * @param heightDp スペーサの高さ(dp)
     */
    private void addSpacer(LinearLayout parent, int heightDp) {
        View spacer = new View(owner);
        parent.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UIComponents.dp(owner, heightDp)));
    }

    /**
     * Provider.values() 内でのインデックスを返す(Spinner初期選択用)。
     * @param p 対象プロバイダー
     * @return values() 内の位置(見つからなければ 0)
     */
    private static int indexOfProvider(RecipeAIService.Provider p) {
        RecipeAIService.Provider[] all = RecipeAIService.Provider.values();
        for (int i = 0; i < all.length; i++) {
            if (all[i] == p) return i;
        }
        return 0;
    }

    /**
     * @return Spinner で現在選択中のプロバイダー
     */
    private RecipeAIService.Provider selectedProvider() {
        Object item = providerSpinner.getSelectedItem();
        return (item instanceof RecipeAIService.Provider)
                ? (RecipeAIService.Provider) item : RecipeAIService.Provider.OPENAI;
    }

    /**
     * Ollama 選択時は APIキー欄を隠して OllamaサーバーURL欄を表示し、
     * それ以外のプロバイダーでは逆にする。
     */
    private void applyKeyVisibility() {
        boolean isOllama = selectedProvider() == RecipeAIService.Provider.OLLAMA;
        keySection.setVisibility(isOllama ? GONE : VISIBLE);
        ollamaSection.setVisibility(isOllama ? VISIBLE : GONE);
    }

    /**
     * モデル一覧を更新する。
     * ステップ1: 静的フォールバックを即時表示し、UIに何か出た状態にする。
     * ステップ2: バックグラウンドで API を叩き、結果が返ったらSpinnerの中身を差し替える。
     */
    private void populateModels() {
        RecipeAIService.Provider p = selectedProvider();
        RecipeAIService service = new RecipeAIService();

        // ステップ1: 静的リストを先に出す
        setModels(Arrays.asList(service.getModelsForProvider(p)), owner.getModelFor(p));

        // ステップ2: 非同期に API から取得
        String currentKey = keyField.getText().toString().trim();
        refreshBtn.setEnabled(false);
        refreshBtn.setText("取得中...");
        UIComponents.runAsync(
                () -> service.fetchModelsForProvider(p, currentKey),
                models -> {
                    refreshBtn.setText("更新");
                    refreshBtn.setEnabled(true);
                    if (models == null || models.length == 0) return;
                    // 現在の選択を維持できるなら維持する
                    Object sel = modelSpinner.getSelectedItem();
                    String currentSel = sel == null ? null : sel.toString();
                    String prefer = (currentSel != null && Arrays.asList(models).contains(currentSel))
                            ? currentSel : owner.getModelFor(p);
                    setModels(Arrays.asList(models), prefer);
                },
                ex -> {
                    // 既に静的フォールバックが入っている
                    refreshBtn.setText("更新");
                    refreshBtn.setEnabled(true);
                });
    }

    /**
     * モデルSpinnerの中身を差し替え、可能なら指定モデルを選択状態にする。
     * @param models   表示するモデル一覧
     * @param selected 選択したいモデル名(null/不在時は先頭)
     */
    private void setModels(List<String> models, String selected) {
        currentModels = new ArrayList<>(models);
        ArrayAdapter<String> adapter = UIComponents.createSpinnerAdapter(owner, currentModels);
        modelSpinner.setAdapter(adapter);
        if (selected != null && !selected.isEmpty()) {
            int idx = currentModels.indexOf(selected);
            if (idx >= 0) modelSpinner.setSelection(idx);
        }
    }
}
