package com.rafael.groksessions;

import android.annotation.SuppressLint;
import android.content.Context;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.webkit.UserAgentMetadata;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Arrays;

import org.json.JSONException;

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
            ReducedUserAgent agent = ReducedUserAgent.from(view.getSettings().getUserAgentString(), seed);
            String script = new String(output.toByteArray(), StandardCharsets.UTF_8)
                    .replace("__NEXORA_PROFILE_SEED__", seed)
                    .replace("__NEXORA_USER_AGENT_CONFIG__", agent == null ? "null" : agent.toJson());
            if (agent != null) applyUserAgent(view.getSettings(), agent);
            WebViewCompat.addDocumentStartJavaScript(view, script, Collections.singleton("*"));
            view.getSettings().setGeolocationEnabled(false);
            return true;
        } catch (IOException | JSONException | UnsupportedOperationException exception) {
            return false;
        }
    }

    @SuppressLint("RequiresFeature")
    private static void applyUserAgent(WebSettings settings, ReducedUserAgent agent) {
        settings.setUserAgentString(agent.userAgent);
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return;
        UserAgentMetadata.BrandVersion webView = new UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Android WebView").setMajorVersion(agent.major).setFullVersion(agent.fullVersion).build();
        UserAgentMetadata.BrandVersion chromium = new UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Chromium").setMajorVersion(agent.major).setFullVersion(agent.fullVersion).build();
        WebSettingsCompat.setUserAgentMetadata(settings, new UserAgentMetadata.Builder()
                .setBrandVersionList(Arrays.asList(webView, chromium)).setFullVersion(agent.fullVersion)
                .setPlatform("Android").setPlatformVersion(agent.platformVersion).setModel(agent.model)
                .setArchitecture("").setBitness(0).setMobile(agent.mobile).setWow64(false).build());
    }
}
