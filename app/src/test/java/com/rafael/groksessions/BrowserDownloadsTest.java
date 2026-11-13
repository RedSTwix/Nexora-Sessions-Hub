package com.rafael.groksessions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BrowserDownloadsTest {
    @Test
    public void removesUnsafeFileNameCharacters() {
        assertEquals("arquivo", BrowserDownloads.safeName(null));
        assertEquals("arquivo", BrowserDownloads.safeName("..."));
        assertEquals("secret_png", BrowserDownloads.safeName("../secret/png"));
        assertEquals("example_.pdf", BrowserDownloads.safeName("example?.pdf"));
    }

    @Test
    public void limitsFileNamesWithoutDiscardingShortExtensions() {
        String safe = BrowserDownloads.safeName("a".repeat(150) + ".pdf");
        assertTrue(safe.length() <= 100);
        assertTrue(safe.endsWith(".pdf"));
    }
}
