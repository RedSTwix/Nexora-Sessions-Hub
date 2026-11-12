package com.rafael.groksessions;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class GitHubReleaseTest {
    private static final String DIGEST = "sha256:2d9ccdac2675f1f2cdcabad9c3f306a3a603363eb1fd504cfce3d77baadc309e";

    private JSONObject release(String version) throws Exception {
        return new JSONObject("{\"tag_name\":\"v" + version + "\",\"draft\":false,\"prerelease\":false,"
                + "\"assets\":[{\"name\":\"Nexora-Sessions-Hub-v" + version + ".apk\",\"state\":\"uploaded\","
                + "\"size\":1055116,\"digest\":\"" + DIGEST + "\",\"browser_download_url\":\""
                + "https://github.com/RedSTwix/Nexora-Sessions-Hub/releases/download/v" + version
                + "/Nexora-Sessions-Hub-v" + version + ".apk\"}]}");
    }

    @Test public void comparesVersionComponentsNumerically() throws Exception {
        GitHubRelease candidate = GitHubRelease.parse(release("0.4.20").toString());
        assertNotNull(candidate);
        assertTrue(candidate.isNewerThan("0.4.9"));
        assertTrue(candidate.isNewerThan("0.4.19"));
        assertFalse(candidate.isNewerThan("0.4.20"));
        assertFalse(candidate.isNewerThan("0.5.0"));
        assertFalse(candidate.isNewerThan("1.0.0"));
    }

    @Test public void comparesDevelopmentBuildToItsBaseVersion() throws Exception {
        GitHubRelease candidate = GitHubRelease.parse(release("0.5.0").toString());
        assertTrue(candidate.isNewerThan("0.4.19-dev"));
        assertFalse(candidate.isNewerThan("0.5.0-dev"));
        assertFalse(candidate.isNewerThan("unknown"));
    }

    @Test public void ignoresDraftAndPrerelease() throws Exception {
        assertNull(GitHubRelease.parse(release("0.5.0").put("draft", true).toString()));
        assertNull(GitHubRelease.parse(release("0.5.0").put("prerelease", true).toString()));
        assertNull(GitHubRelease.parse(release("0.5.0").put("tag_name", "v0.5.0-rc1").toString()));
    }

    @Test public void ignoresMissingOrUnfinishedApk() throws Exception {
        assertNull(GitHubRelease.parse(release("0.5.0").put("assets", new org.json.JSONArray()).toString()));
        JSONObject response = release("0.5.0");
        response.getJSONArray("assets").getJSONObject(0).put("state", "starter");
        assertNull(GitHubRelease.parse(response.toString()));
    }

    @Test public void rejectsDownloadsOutsideTheOfficialRelease() throws Exception {
        String[] urls = {
                "http://github.com/RedSTwix/Nexora-Sessions-Hub/releases/download/v0.5.0/Nexora-Sessions-Hub-v0.5.0.apk",
                "https://github.com.evil.example/RedSTwix/Nexora-Sessions-Hub/releases/download/v0.5.0/Nexora-Sessions-Hub-v0.5.0.apk",
                "https://github.com/other/repository/releases/download/v0.5.0/Nexora-Sessions-Hub-v0.5.0.apk",
                "https://github.com/RedSTwix/Nexora-Sessions-Hub/releases/download/v0.4.19/Nexora-Sessions-Hub-v0.5.0.apk",
                "https://user@github.com/RedSTwix/Nexora-Sessions-Hub/releases/download/v0.5.0/Nexora-Sessions-Hub-v0.5.0.apk"
        };
        for (String url : urls) {
            JSONObject response = release("0.5.0");
            response.getJSONArray("assets").getJSONObject(0).put("browser_download_url", url);
            assertNull(url, GitHubRelease.parse(response.toString()));
        }
    }

    @Test public void requiresDigestAndPositiveSize() throws Exception {
        for (String digest : new String[]{"", "sha256:invalid", "sha1:1234"}) {
            JSONObject response = release("0.5.0");
            response.getJSONArray("assets").getJSONObject(0).put("digest", digest);
            assertNull(GitHubRelease.parse(response.toString()));
        }
        JSONObject response = release("0.5.0");
        response.getJSONArray("assets").getJSONObject(0).put("size", 0);
        assertNull(GitHubRelease.parse(response.toString()));
    }

    @Test public void restoresPersistedReleaseWithoutChangingItsIntegrityMetadata() throws Exception {
        GitHubRelease candidate = GitHubRelease.parse(release("0.5.0").toString());
        GitHubRelease restored = GitHubRelease.parse(candidate.toJson());
        assertNotNull(restored);
        assertEquals(candidate.version, restored.version);
        assertEquals(candidate.downloadUrl, restored.downloadUrl);
        assertEquals(candidate.sha256, restored.sha256);
        assertEquals(candidate.size, restored.size);
    }
}
