package com.rafael.groksessions;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public final class SiteAssistantTest {
    @Test public void registryOnlyEnablesTheExactHttpsGrokGenerator() {
        assertTrue(SiteAssistant.supports("https://vidogrid.ai/app/video-gen"));
        assertTrue(SiteAssistant.supports("https://vidogrid.ai/app/video-gen/grok/"));
        assertTrue(SiteAssistant.supports("https://vidogrid.ai:443/app/video-gen/grok?source=shortcut#output"));
        for (String url : new String[]{null, "", "http://vidogrid.ai/app/video-gen/grok",
                "https://vidogrid.ai.evil.test/app/video-gen/grok", "https://other.vidogrid.ai/app/video-gen/grok",
                "https://vidogrid.ai:8443/app/video-gen/grok", "https://vidogrid.ai/history",
                "https://vidogrid.ai/app/video-gen/veo", "https://vidogrid.ai/app/video-gen/grok/other"})
            assertFalse(String.valueOf(url), SiteAssistant.supports(url));
    }
}
