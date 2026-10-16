package main.java.UI;

import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import main.java.MainActivity;
import main.java.AI.AiSettings;
import main.java.AI.RecipeAIService;

import java.util.Arrays;
import java.util.List;

/**
 * AI利用設定画面パネル。
 * APIキーは OpenAI / Gemini / Claude それぞれで個別に保存され、
 * プロバイダーを切替えると該当プロバイダーの保存済みキーが復元される。
 * Ollama / llama.cpp 選択時は APIキーの代わりにサーバーのURLを設定する。
 *
 * モデル一覧は次のタイミングで API から動的取得する:
 *   - プロバイダー切替時
 *   - APIキー / サーバーURL欄からフォーカスが外れた時(入力直後)
 *   - 「更新」ボタン押下時
 * 取得失敗時は各プロバイダーの静的フォールバックリストにフォールバックする。
 */
public class SettingsPanel extends ScrollView {

    private static final String REFRESH_LABEL = "更新";

    private final AiSettings ai;
    private final Spinner providerSpinner;
    private final Spinner modelSpinner;
    private final Button refreshBtn;
    private final EditText keyField;
    private final EditText urlField;
    private final LinearLayout keySection;
    private final LinearLayout urlSection;
    private final TextView urlLabel;

    /**
     * AI設定パネルを構築する。プロバイダー切替で該当プロバイダーの保存済みモデル/キーを復元し、
     * モデル一覧は API から非同期で動的取得する。
     * @param owner 共有状態(AI利用設定)を保持するアクティビティ
     */
    public SettingsPanel(MainActivity owner) {
        super(owner);
        this.ai = owner.getAiSettings();

        LinearLayout form = new LinearLayout(owner);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = UIComponents.dp(owner, 16);
        form.setPadding(pad, pad, pad, pad);
        addView(form, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        form.addView(UIComponents.createTitleLabel(owner, "AI利用設定"));
        UIComponents.addSpacer(form, 16);

        form.addView(UIComponents.createLabel(owner, "利用するAIプロバイダー:"));
        providerSpinner = new Spinner(owner);
        RecipeAIService.Provider[] providers = RecipeAIService.Provider.values();
        providerSpinner.setAdapter(UIComponents.createSpinnerAdapter(owner, providers));
        providerSpinner.setSelection(Arrays.asList(providers).indexOf(ai.getProvider()));
        form.addView(providerSpinner);
        UIComponents.addSpacer(form, 12);

        // モデル選択と更新ボタンを横並びにする
        form.addView(UIComponents.createLabel(owner, "利用するモデル:"));
        LinearLayout modelRow = new LinearLayout(owner);
        modelRow.setOrientation(LinearLayout.HORIZONTAL);
        modelRow.setGravity(Gravity.CENTER_VERTICAL);
        modelSpinner = new Spinner(owner);
        modelRow.addView(modelSpinner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        refreshBtn = UIComponents.createButton(owner, REFRESH_LABEL);
        refreshBtn.setOnClickListener(v -> populateModels());
        modelRow.addView(refreshBtn);
        form.addView(modelRow);

        // APIキー欄はローカルLLM(Ollama / llama.cpp)選択時に非表示にしたいので、ラベル+入力欄を
        // 1つのコンテナにまとめて表示/非表示を一括制御する
        keySection = new LinearLayout(owner);
        keySection.setOrientation(LinearLayout.VERTICAL);
        UIComponents.addSpacer(keySection, 12);
        keySection.addView(UIComponents.createLabel(owner, "APIキー (プロバイダーごとに別々に保存されます):"));
        keyField = UIComponents.createPasswordField(owner);
        keySection.addView(keyField);
        form.addView(keySection);

        // ローカルLLM選択時だけ表示するサーバーURL欄
        // (スマートフォンからは PC 上のサーバーへ LAN 経由で接続することが多いため、Ollama も URL を設定できる)
        urlSection = new LinearLayout(owner);
        urlSection.setOrientation(LinearLayout.VERTICAL);
        UIComponents.addSpacer(urlSection, 12);
        urlLabel = UIComponents.createLabel(owner, "");
        urlSection.addView(urlLabel);
        urlField = UIComponents.createStyledTextField(owner);
        urlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlSection.addView(urlField);
        TextView urlHint = UIComponents.createLabel(owner,
                "PC のサーバーに接続する場合は、PC 側で LAN からの接続を許可して起動してください"
                + "(Ollama: 環境変数 OLLAMA_HOST=0.0.0.0 / llama-server: --host 0.0.0.0)。"
                + "Android エミュレータからホスト PC へは 10.0.2.2 で接続できます。");
        urlHint.setTextColor(Theme.COLOR_MUTED);
        urlHint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
        urlSection.addView(urlHint);
        form.addView(urlSection);

        // プロバイダー切替時(初回表示を含む)にキー/URL欄の表示と中身を更新し、モデル一覧も再取得
        providerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                RecipeAIService.Provider p = selectedProvider();
                keyField.setText(ai.getApiKey(p));
                urlField.setText(ai.getServerUrl(p));
                applySectionVisibility(p);
                populateModels();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // APIキー / URL 入力後フォーカスが外れたタイミングでモデル一覧を更新
        View.OnFocusChangeListener refreshOnFocusLost = (v, hasFocus) -> {
            if (!hasFocus) populateModels();
        };
        keyField.setOnFocusChangeListener(refreshOnFocusLost);
        urlField.setOnFocusChangeListener(refreshOnFocusLost);

        UIComponents.addSpacer(form, 24);
        Button btnSave = UIComponents.createPrimaryButton(owner, "設定を保存");
        btnSave.setOnClickListener(v -> {
            RecipeAIService.Provider selected = selectedProvider();
            ai.setProvider(selected);
            ai.setModel(selected, UIComponents.selectedItem(modelSpinner));
            // ローカルLLMはキー不要なので保存スキップ
            if (selected.requiresApiKey()) {
                ai.setApiKey(selected, keyField.getText().toString().trim());
            } else {
                ai.setServerUrl(selected, urlField.getText().toString());
            }
            UIComponents.info(owner, "設定を保存しました");
            owner.showWelcome();
        });
        form.addView(btnSave);
    }

    /**
     * @return プロバイダー選択欄で選ばれているプロバイダー
     */
    private RecipeAIService.Provider selectedProvider() {
        return UIComponents.selectedItem(providerSpinner);
    }

    /**
     * ローカルLLMはAPIキー不要なので、選択時はキー欄を丸ごと非表示にしてURL欄を出す。
     * @param p 選択中のプロバイダー
     */
    private void applySectionVisibility(RecipeAIService.Provider p) {
        keySection.setVisibility(p.requiresApiKey() ? VISIBLE : GONE);
        urlSection.setVisibility(p.isLocal() ? VISIBLE : GONE);
        if (p == RecipeAIService.Provider.LLAMA_CPP) {
            urlLabel.setText("llama-server のURL (起動時に表示される listening on のアドレス。例: http://192.168.1.10:9931):");
            urlField.setHint("http://192.168.1.10:9931");
        } else {
            urlLabel.setText("Ollama サーバーのURL (空欄なら端末内の http://localhost:11434。例: http://192.168.1.10:11434):");
            urlField.setHint("http://localhost:11434");
        }
    }

    /**
     * モデル一覧を更新する。
     * ステップ1: 静的フォールバックを即時表示し、UIに何か出た状態にする
     * ステップ2: バックグラウンドで API を叩き、結果が返ったら一覧の中身を差し替える
     */
    private void populateModels() {
        RecipeAIService.Provider p = selectedProvider();
        if (p == null) return;

        // ステップ1: 静的リストを先に出す
        String[] defaults = RecipeAIService.defaultModels(p);
        setModels(defaults, ai.getModel(p));

        // ステップ2: 非同期に API から取得
        String currentKey = keyField.getText().toString().trim();
        String currentUrl = urlField.getText().toString().trim();
        refreshBtn.setEnabled(false);
        refreshBtn.setText("取得中...");
        UIComponents.runAsync(
                () -> RecipeAIService.fetchModels(p, currentKey, currentUrl),
                models -> {
                    refreshBtn.setText(REFRESH_LABEL);
                    refreshBtn.setEnabled(true);
                    // 取得中に別のプロバイダーへ切り替えられていたら結果を捨てる
                    if (selectedProvider() != p || models == null || models.length == 0) return;
                    // 現在の選択を維持できるなら維持する
                    String currentSel = UIComponents.selectedItem(modelSpinner);
                    setModels(models, currentSel != null && Arrays.asList(models).contains(currentSel)
                            ? currentSel : ai.getModel(p));
                },
                ex -> {
                    // 既に静的フォールバックが入っている
                    refreshBtn.setText(REFRESH_LABEL);
                    refreshBtn.setEnabled(true);
                });
    }

    /**
     * モデル一覧の中身を差し替え、指定モデルがあれば選択する。
     * @param models   表示するモデル名
     * @param selected 選択したいモデル名(null可)
     */
    private void setModels(String[] models, String selected) {
        List<String> list = Arrays.asList(models);
        modelSpinner.setAdapter(UIComponents.createSpinnerAdapter(getContext(), list));
        int idx = selected == null ? -1 : list.indexOf(selected);
        if (idx >= 0) modelSpinner.setSelection(idx);
    }
}
