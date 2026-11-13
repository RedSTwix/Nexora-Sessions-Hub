package com.rafael.groksessions;

import android.annotation.SuppressLint;
import android.content.Context;
import android.webkit.WebView;

import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

final class ScrollKeyboard {
    private ScrollKeyboard() {}

    @SuppressLint("RequiresFeature")
    static void attach(Context context, WebView view, Runnable hideKeyboard) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
        try (InputStream input = context.getResources().openRawResource(R.raw.scroll_keyboard);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            WebViewCompat.addWebMessageListener(view, "__nexoraScrollKeyboard", Collections.singleton("*"),
                    (source, message, origin, mainFrame, reply) -> {
                        // Frames can dismiss the keyboard too; this bridge only accepts this one command.
                        if (origin != null && ("https".equals(origin.getScheme()) || "http".equals(origin.getScheme()))
                                && message.getType() == WebMessageCompat.TYPE_STRING && "hide".equals(message.getData()))
                            hideKeyboard.run();
                    });
            WebViewCompat.addDocumentStartJavaScript(view,
                    new String(output.toByteArray(), StandardCharsets.UTF_8), Collections.singleton("*"));
        } catch (IOException | RuntimeException ignored) { /* Keep native editing behavior if unsupported. */ }
    }
}
