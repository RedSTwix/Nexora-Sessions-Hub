package com.rafael.groksessions;

import android.annotation.SuppressLint;
import android.content.Context;
import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

final class ProfilePrivacy {
    private ProfilePrivacy() {}

    @SuppressLint("RequiresFeature")
    static boolean apply(Context context, WebView view, String seed) {
        if (seed == null || !seed.matches("[0-9a-f]{64}")
                || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            return false;
        }
        try (InputStream stream = context.getResources().openRawResource(R.raw.profile_privacy);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            String script = new String(output.toByteArray(), StandardCharsets.UTF_8)
                    .replace("__NEXORA_PROFILE_SEED__", seed);
            WebViewCompat.addDocumentStartJavaScript(view, script, Collections.singleton("*"));
            view.getSettings().setGeolocationEnabled(false);
            return true;
        } catch (IOException | UnsupportedOperationException exception) {
            return false;
        }
    }
}
