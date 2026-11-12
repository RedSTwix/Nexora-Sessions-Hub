package com.rafael.groksessions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigInteger;
import java.net.URI;
import java.util.regex.Pattern;

/** Only complete, stable releases from the project's official repository are eligible. */
final class GitHubRelease {
    static final String REPOSITORY = "RedSTwix/Nexora-Sessions-Hub";
    static final String API_URL = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    private static final Pattern VERSION = Pattern.compile("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)");

    final String version;
    final String downloadUrl;
    final String sha256;
    final long size;

    private GitHubRelease(String version, String downloadUrl, String sha256, long size) {
        this.version = version;
        this.downloadUrl = downloadUrl;
        this.sha256 = sha256;
        this.size = size;
    }

    static GitHubRelease parse(String json) throws JSONException {
        JSONObject release = new JSONObject(json);
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) {
            return null;
        }
        String tag = release.getString("tag_name");
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        if (!VERSION.matcher(version).matches()) {
            return null;
        }
        String expectedName = "Nexora-Sessions-Hub-v" + version + ".apk";
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) {
            return null;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (!expectedName.equals(asset.optString("name"))
                    || !"uploaded".equals(asset.optString("state"))) {
                continue;
            }
            String url = asset.getString("browser_download_url");
            String digest = asset.optString("digest");
            long size = asset.optLong("size");
            if (!isOfficialDownload(url, tag, expectedName)
                    || !digest.matches("sha256:[a-fA-F0-9]{64}") || size <= 0) {
                return null;
            }
            return new GitHubRelease(version, url, digest.substring(7), size);
        }
        return null;
    }

    private static boolean isOfficialDownload(String url, String tag, String name) {
        try {
            URI uri = URI.create(url);
            return "https".equals(uri.getScheme()) && "github.com".equals(uri.getHost())
                    && uri.getPort() == -1 && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null
                    && ("/" + REPOSITORY + "/releases/download/" + tag + "/" + name).equals(uri.getPath());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    boolean isNewerThan(String installedVersion) {
        String base = installedVersion.split("-", 2)[0];
        if (!VERSION.matcher(base).matches()) {
            return false;
        }
        String[] candidate = version.split("\\.");
        String[] installed = base.split("\\.");
        for (int i = 0; i < 3; i++) {
            int comparison = new BigInteger(candidate[i]).compareTo(new BigInteger(installed[i]));
            if (comparison != 0) {
                return comparison > 0;
            }
        }
        return false;
    }

    String toJson() throws JSONException {
        JSONObject asset = new JSONObject().put("name", "Nexora-Sessions-Hub-v" + version + ".apk")
                .put("state", "uploaded").put("browser_download_url", downloadUrl)
                .put("digest", "sha256:" + sha256).put("size", size);
        String tag = URI.create(downloadUrl).getPath().split("/")[5];
        return new JSONObject().put("tag_name", tag)
                .put("assets", new JSONArray().put(asset)).toString();
    }
}
