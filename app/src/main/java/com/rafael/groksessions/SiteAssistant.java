package com.rafael.groksessions;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Uri;
import android.webkit.WebView;
import android.widget.Toast;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** A closed-by-default assistant in the current profile's main WebView. No native JS bridge. */
final class SiteAssistant {
    private final Context context;
    private final WebView view;
    private ScriptHandler registration;
    private String source;

    SiteAssistant(Context context, WebView view) {
        this.context = context; this.view = view;
    }

    @SuppressLint("RequiresFeature")
    void attach() {
        try {
            source = read(R.raw.vidogrid_preload) + "\n" + read(R.raw.vidogrid_assistant);
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                registration = WebViewCompat.addDocumentStartJavaScript(view, source,
                        Collections.singleton("https://vidogrid.ai"));
            refresh();
        } catch (IOException | RuntimeException exception) { source = null; }
    }

    void refresh() {
        if (source != null && supports(view.getUrl())) view.evaluateJavascript(source, null);
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
        view.evaluateJavascript(source + "\nwindow.__nexoraSiteAssistant?.toggle() ?? false;", result -> {
            if (!"true".equals(result)) Toast.makeText(context,
                    "Abra a geração com Grok do Vidogrid para usar este assistente.", Toast.LENGTH_SHORT).show();
        });
    }

    void pause() {
        if (supports(view.getUrl())) view.evaluateJavascript("window.__nexoraVidoAssistant?.pause();", null);
    }

    void close() {
        if (supports(view.getUrl())) view.evaluateJavascript("window.__nexoraSiteAssistant?.destroy();", null);
        if (registration != null) { registration.remove(); registration = null; }
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
