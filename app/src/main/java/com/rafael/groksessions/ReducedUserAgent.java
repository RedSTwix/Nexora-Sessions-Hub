package com.rafael.groksessions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Common reduced Android identity; never impersonates a different browser or device model. */
final class ReducedUserAgent {
    private static final Pattern CHROME_VERSION = Pattern.compile("\\bChrome/([1-9][0-9]*)\\.[0-9]+\\.[0-9]+\\.[0-9]+");
    final String major;
    final String fullVersion;
    final boolean mobile;
    final String userAgent;

    private ReducedUserAgent(String major, boolean mobile) {
        this.major = major;
        this.fullVersion = major + ".0.0.0";
        this.mobile = mobile;
        userAgent = "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Version/4.0 Chrome/" + fullVersion + (mobile ? " Mobile" : "") + " Safari/537.36";
    }

    static ReducedUserAgent from(String original) {
        if (original == null || !original.contains("Android")) return null;
        Matcher match = CHROME_VERSION.matcher(original);
        return match.find() ? new ReducedUserAgent(match.group(1), original.contains("Mobile")) : null;
    }

    String toJson() throws JSONException {
        JSONArray brands = new JSONArray()
                .put(new JSONObject().put("brand", "Android WebView").put("version", major))
                .put(new JSONObject().put("brand", "Chromium").put("version", major));
        return new JSONObject().put("userAgent", userAgent).put("major", major)
                .put("fullVersion", fullVersion).put("mobile", mobile).put("brands", brands).toString();
    }
}
