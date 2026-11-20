package com.rafael.groksessions;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.webkit.WebView;
import android.widget.Toast;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** A closed-by-default assistant in the current profile's main WebView. */
final class SiteAssistant {
    private static final String SETTINGS_BRIDGE = "__nexoraSiteSettings";
    private final Context context;
    private final WebView view;
    private final String profileId;
    private final SiteAssistantSettings settings;
    private ScriptHandler registration;
    private String source;
    private boolean settingsBridge, closed;
    private AlertDialog settingsDialog;

    SiteAssistant(Context context, WebView view, String profileId) {
        this.context = context; this.view = view;
        this.profileId = profileId; settings = new SiteAssistantSettings(context);
    }

    @SuppressLint("RequiresFeature")
    void attach() {
        try {
            source = read(R.raw.vidogrid_preload) + "\n" + read(R.raw.vidogrid_assistant);
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(view, SETTINGS_BRIDGE, Collections.singleton("https://vidogrid.ai"),
                        (sender, message, origin, mainFrame, reply) -> {
                            if (closed || sender != view || message.getType() != WebMessageCompat.TYPE_STRING
                                    || !allowsSettingsRequest(origin, mainFrame, message.getData()) || !supports(view.getUrl())) return;
                            view.evaluateJavascript("window.__nexoraSiteAssistant?.visible()===true", visible -> {
                                if (!closed && "true".equals(visible) && supports(view.getUrl())) showSettings();
                            });
                        });
                settingsBridge = true;
            }
            registerScript();
            refresh();
        } catch (IOException | RuntimeException exception) { source = null; }
    }

    void refresh() {
        if (!closed && source != null && supports(view.getUrl())) view.evaluateJavascript(configuredSource(), null);
    }

    void toggle() {
        if (!supports(view.getUrl())) {
            Toast.makeText(context, "Ainda não há assistente para esta página.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (source == null) {
            Toast.makeText(context, "Não foi possível carregar o assistente.", Toast.LENGTH_SHORT).show();
            return;
        }
        view.evaluateJavascript(configuredSource() + "\nwindow.__nexoraSiteAssistant?.toggle() ?? false;", result -> {
            if (!"true".equals(result)) Toast.makeText(context,
                    "Abra a geração com Grok do Vidogrid para usar este assistente.", Toast.LENGTH_SHORT).show();
        });
    }

    void pause() {
        if (supports(view.getUrl())) view.evaluateJavascript("window.__nexoraVidoAssistant?.pause();", null);
    }

    void close() {
        closed = true;
        if (settingsDialog != null) { settingsDialog.dismiss(); settingsDialog = null; }
        if (supports(view.getUrl())) view.evaluateJavascript("window.__nexoraSiteAssistant?.destroy();", null);
        if (registration != null) { registration.remove(); registration = null; }
        if (settingsBridge) WebViewCompat.removeWebMessageListener(view, SETTINGS_BRIDGE);
    }

    static boolean allowsSettingsRequest(Uri origin, boolean mainFrame, String message) {
        return mainFrame && origin != null && "https".equals(origin.getScheme())
                && "vidogrid.ai".equals(origin.getHost()) && (origin.getPort() == -1 || origin.getPort() == 443)
                && "settings".equals(message);
    }

    private String configuredSource() {
        return source + "\nwindow.__nexoraSiteAssistant?.setAcceptedLimit(" + settings.acceptedLimit(profileId) + ");";
    }

    @SuppressLint("RequiresFeature")
    private void registerScript() {
        if (registration != null) { registration.remove(); registration = null; }
        if (source != null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
            registration = WebViewCompat.addDocumentStartJavaScript(view, configuredSource(),
                    Collections.singleton("https://vidogrid.ai"));
    }

    private void showSettings() {
        if (!(context instanceof Activity) || ((Activity) context).isFinishing()
                || (settingsDialog != null && settingsDialog.isShowing())) return;
        pause();
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * context.getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding / 2, padding, padding / 2);
        TextView description = new TextView(context);
        description.setText("Pausar após quantas solicitações aceitas?\n0 = sem limite. Padrão: 5.\n\nO valor fica salvo somente neste perfil.");
        description.setTextSize(14); layout.addView(description);
        EditText limit = new EditText(context);
        limit.setInputType(InputType.TYPE_CLASS_NUMBER); limit.setSingleLine(true);
        limit.setContentDescription("Limite de solicitações aceitas");
        limit.setText(Integer.toString(settings.acceptedLimit(profileId))); limit.setSelectAllOnFocus(true);
        layout.addView(limit);
        TextView note = new TextView(context);
        note.setText("A repetição fica pausada durante a configuração. Após salvar, retome pelo painel.");
        note.setTextSize(13); layout.addView(note);
        settingsDialog = new AlertDialog.Builder(context).setTitle("Configurações do assistente")
                .setView(layout).setNegativeButton("Cancelar", null).setPositiveButton("Salvar", null).create();
        settingsDialog.setOnShowListener(dialog -> settingsDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            if (closed || settingsDialog == null) return;
            final int value;
            try { value = Integer.parseInt(limit.getText().toString().trim()); }
            catch (NumberFormatException exception) { limit.setError("Informe um número inteiro a partir de 0."); return; }
            if (value < 0) { limit.setError("Informe um número a partir de 0."); return; }
            if (!settings.saveAcceptedLimit(profileId, value)) {
                limit.setError("Não foi possível salvar. Tente novamente."); return;
            }
            registerScript(); refresh(); settingsDialog.dismiss();
        }));
        settingsDialog.show();
    }

    /** Registry: new site integrations can be added here without changing the toolbar button. */
    static boolean supports(String value) {
        if (value == null) return false;
        Uri uri = Uri.parse(value);
        if (!"https".equals(uri.getScheme()) || !"vidogrid.ai".equals(uri.getHost())
                || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
        String path = uri.getPath();
        return "/app/video-gen".equals(path) || "/app/video-gen/".equals(path)
                || "/app/video-gen/grok".equals(path) || "/app/video-gen/grok/".equals(path);
    }

    private String read(int resource) throws IOException {
        try (InputStream input = context.getResources().openRawResource(resource);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
