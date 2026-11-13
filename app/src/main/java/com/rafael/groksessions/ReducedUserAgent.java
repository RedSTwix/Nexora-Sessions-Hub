package com.rafael.groksessions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Generated Android identification; the actual Chromium engine and WebView marker are preserved. */
final class ReducedUserAgent {
    private static final Pattern CHROME_VERSION = Pattern.compile("\\bChrome/([1-9][0-9]*)\\.[0-9]+\\.[0-9]+\\.[0-9]+");
    // Versioned pool: keep order and length fixed so saved profile seeds retain their identity.
    private static final String[][] PRESETS_V1 = {
            {"10", "Pixel 4"}, {"11", "Pixel 4a"}, {"12", "Pixel 6"},
            {"13", "Pixel 7"}, {"14", "Pixel 8"}
    };
    final String major;
    final String fullVersion;
    final boolean mobile;
    final String userAgent;
    final String androidVersion;
    final String model;
    final String platformVersion;

    private ReducedUserAgent(String major, boolean mobile, String androidVersion, String model) {
        this.major = major;
        this.fullVersion = major + ".0.0.0";
        this.mobile = mobile;
        this.androidVersion = androidVersion;
        this.model = model;
        this.platformVersion = androidVersion + ".0.0";
        userAgent = "Mozilla/5.0 (Linux; Android " + androidVersion + "; " + (model.isEmpty() ? "K" : model)
                + "; wv) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Version/4.0 Chrome/" + fullVersion + (mobile ? " Mobile" : "") + " Safari/537.36";
    }

    static ReducedUserAgent from(String original) {
        return from(original, null);
    }

    static ReducedUserAgent from(String original, String seed) {
        if (original == null || !original.contains("Android")) return null;
        Matcher match = CHROME_VERSION.matcher(original);
        if (!match.find()) return null;
        boolean mobile = original.contains("Mobile");
        // Phone presets must not turn a real tablet into a phone or fabricate an engine version.
        if (mobile && seed != null && seed.matches("[0-9a-f]{64}")) {
            int index = (int) (Long.parseLong(seed.substring(0, 8), 16) % PRESETS_V1.length);
            String[] preset = PRESETS_V1[index];
            return new ReducedUserAgent(match.group(1), true, preset[0], preset[1]);
        }
        return new ReducedUserAgent(match.group(1), mobile, "10", "");
    }

    String toJson() throws JSONException {
        JSONArray brands = new JSONArray()
                .put(new JSONObject().put("brand", "Android WebView").put("version", major))
                .put(new JSONObject().put("brand", "Chromium").put("version", major));
        return new JSONObject().put("userAgent", userAgent).put("major", major)
                .put("fullVersion", fullVersion).put("mobile", mobile).put("brands", brands)
                .put("platformVersion", platformVersion).put("model", model).toString();
    }
}
