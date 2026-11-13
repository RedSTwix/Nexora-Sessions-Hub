package com.rafael.groksessions;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public final class DownloadFileNamesTest {
    @Test public void fixesBinNamesFromTheVideoResponseAndPreservesDispositionNames() {
        assertEquals("20261113_120655_UTC_0.mp4", DownloadFileNames.choose("https://cdn.example.test/opaque",
                "attachment; filename=\"20261113_120655_UTC_0.bin\"", "video/mp4"));
        assertEquals("clipe.webm", DownloadFileNames.choose("https://cdn.example.test/opaque",
                "attachment; filename=\"clipe.bin\"", "video/webm"));
        assertEquals("relatorio.pdf", DownloadFileNames.choose("https://cdn.example.test/opaque",
                "attachment; filename=\"relatorio.pdf\"", "application/pdf"));
    }
}
