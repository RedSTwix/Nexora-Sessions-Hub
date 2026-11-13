package com.rafael.groksessions;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class ReducedUserAgentTest {
    private static final String ORIGINAL = "Mozilla/5.0 (Linux; Android 14; 22111317PG Build/UKQ1.231003.002; wv) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/156.0.8078.47 Mobile Safari/537.36";

    @Test public void removesModelBuildAndDetailedVersionsWithoutChangingBrowserIdentity() {
        ReducedUserAgent agent = ReducedUserAgent.from(ORIGINAL);
        assertNotNull(agent);
        assertEquals("156", agent.major);
        assertEquals("156.0.0.0", agent.fullVersion);
        assertTrue(agent.userAgent.contains("Android 10; K; wv"));
        assertTrue(agent.userAgent.contains("Version/4.0 Chrome/156.0.0.0 Mobile"));
        for (String sensitive : new String[]{"22111317PG", "UKQ1.231003.002", "Android 14", "8078.47", "Build/"}) {
            assertFalse(sensitive, agent.userAgent.contains(sensitive));
        }
    }

    @Test public void producesMatchingConfigurationForJavascriptAndNativeHeaders() throws Exception {
        ReducedUserAgent agent = ReducedUserAgent.from(ORIGINAL);
        JSONObject config = new JSONObject(agent.toJson());
        assertEquals(agent.userAgent, config.getString("userAgent"));
        assertEquals(agent.fullVersion, config.getString("fullVersion"));
        assertTrue(config.getBoolean("mobile"));
        assertEquals("Android WebView", config.getJSONArray("brands").getJSONObject(0).getString("brand"));
        assertEquals("156", config.getJSONArray("brands").getJSONObject(1).getString("version"));
    }

    @Test public void preservesTabletFormFactorAndUsesTheActualEngineMajor() {
        ReducedUserAgent agent = ReducedUserAgent.from(ORIGINAL.replace(" Mobile", "").replace("Chrome/156.", "Chrome/157."));
        assertNotNull(agent);
        assertEquals("157", agent.major);
        assertFalse(agent.mobile);
        assertFalse(agent.userAgent.contains("Mobile"));
        assertTrue(agent.userAgent.contains("Chrome/157.0.0.0"));
    }

    @Test public void leavesUnsupportedAgentsUntouchedRatherThanInventingAVersion() {
        assertNull(ReducedUserAgent.from(null));
        assertNull(ReducedUserAgent.from("Unknown Android WebView"));
        assertNull(ReducedUserAgent.from("Mozilla/5.0 Chrome/156.0.8078.47"));
    }

    @Test public void profileSeedsSelectStableCoherentAndroidAndModelPairs() throws Exception {
        String[] versions = {"10", "11", "12", "13", "14"};
        String[] models = {"Pixel 4", "Pixel 4a", "Pixel 6", "Pixel 7", "Pixel 8"};
        for (int i = 0; i < versions.length; i++) {
            String seed = String.format(java.util.Locale.ROOT, "%08x", i) + "a1".repeat(28);
            ReducedUserAgent first = ReducedUserAgent.from(ORIGINAL, seed);
            ReducedUserAgent reopened = ReducedUserAgent.from(ORIGINAL, seed);
            ReducedUserAgent updatedEngine = ReducedUserAgent.from(ORIGINAL.replace("Chrome/156.", "Chrome/157."), seed);
            assertEquals(versions[i], first.androidVersion);
            assertEquals(models[i], first.model);
            assertEquals(first.userAgent, reopened.userAgent);
            assertEquals(first.model, updatedEngine.model);
            assertEquals(first.platformVersion, updatedEngine.platformVersion);
            assertTrue(first.userAgent.contains("Android " + versions[i] + "; " + models[i] + "; wv"));
            assertTrue(first.userAgent.contains("Chrome/156.0.0.0"));
            assertFalse(first.userAgent.contains("Build/"));
            assertFalse(first.userAgent.contains("22111317PG"));
            JSONObject metadata = new JSONObject(first.toJson());
            assertEquals(models[i], metadata.getString("model"));
            assertEquals(versions[i] + ".0.0", metadata.getString("platformVersion"));
        }
    }

    @Test public void phonePresetsDoNotChangeTabletFormFactor() {
        ReducedUserAgent tablet = ReducedUserAgent.from(ORIGINAL.replace(" Mobile", ""), "a1".repeat(32));
        assertFalse(tablet.mobile);
        assertEquals("", tablet.model);
        assertTrue(tablet.userAgent.contains("Android 10; K; wv"));
    }
}
