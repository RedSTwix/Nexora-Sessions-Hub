package com.rafael.groksessions;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import static org.junit.Assert.*;

public final class DownloadStreamTest {
    @Test public void preservesBytesAcrossOrderedChunksAndCompletesOnlyAtExactSize() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DownloadStream stream = new DownloadStream(output, 70000);
        byte[] first = new byte[65536];
        for (int i = 0; i < first.length; i++) first[i] = (byte) i;
        byte[] last = new byte[4464];
        stream.write(0, first);
        stream.write(1, last);
        stream.finish(2);
        assertEquals(70000, stream.received());
        assertEquals(70000, output.size());
        assertArrayEquals(first, java.util.Arrays.copyOf(output.toByteArray(), first.length));
        assertThrows(IOException.class, () -> stream.write(2, new byte[]{1}));
    }

    @Test public void rejectsMissingDuplicateOversizedAndExcessChunks() throws Exception {
        DownloadStream stream = new DownloadStream(new ByteArrayOutputStream(), 5);
        assertThrows(IOException.class, () -> stream.write(1, new byte[]{1}));
        assertThrows(IOException.class, () -> stream.write(0, new byte[65537]));
        assertThrows(IOException.class, () -> stream.write(0, new byte[6]));
        assertThrows(IOException.class, () -> stream.write(0, new byte[0]));
        stream.write(0, new byte[]{1, 2});
        assertThrows(IOException.class, () -> stream.write(0, new byte[]{3}));
        assertThrows(IOException.class, () -> stream.finish(1));
        stream.close();
        assertThrows(IOException.class, () -> stream.write(1, new byte[]{3}));
    }

    @Test public void doesNotPublishWhenTheDestinationFails() throws Exception {
        DownloadStream stream = new DownloadStream(new java.io.OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("Sem espaço"); }
        }, 1);
        assertThrows(IOException.class, () -> stream.write(0, new byte[]{1}));
        assertEquals(0, stream.received());
        assertThrows(IOException.class, () -> stream.finish(0));
    }

    @Test public void repairsGenericVideoExtensionsWithoutRenamingKnownNonVideoFiles() {
        assertEquals("video.mp4", DownloadFileNames.correctExtension("video.bin", "video/mp4"));
        assertEquals("video.webm", DownloadFileNames.correctExtension("video", "video/webm"));
        assertEquals("clip.mov", DownloadFileNames.correctExtension("clip.BIN", "video/quicktime"));
        assertEquals("dados.bin", DownloadFileNames.correctExtension("dados.bin", "application/octet-stream"));
        assertEquals("video/mp4", DownloadFileNames.mediaType("application/octet-stream", "https://example.test/clip.mp4?token=example"));
        assertEquals("video/webm", DownloadFileNames.mediaType("video/webm; codecs=vp9", null));
        assertEquals("video/mp4", DownloadFileNames.sniff(new byte[]{0,0,0,24,'f','t','y','p','i','s','o','m'}, "application/octet-stream"));
        assertEquals("video/webm", DownloadFileNames.sniff(new byte[]{0x1a,0x45,(byte)0xdf,(byte)0xa3}, "application/octet-stream"));
        assertEquals("application/octet-stream", DownloadFileNames.sniff(new byte[]{1,2,3}, "application/octet-stream"));
    }
}
