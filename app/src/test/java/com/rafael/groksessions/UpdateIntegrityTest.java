package com.rafael.groksessions;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class UpdateIntegrityTest {
    private static final String ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private File write(String contents) throws Exception {
        File file = temporary.newFile();
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write(contents.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    @Test public void acceptsExactContent() throws Exception {
        assertTrue(UpdateIntegrity.matches(write("abc"), 3, ABC_SHA256));
    }

    @Test public void rejectsTamperingEvenWhenSizeIsUnchanged() throws Exception {
        assertFalse(UpdateIntegrity.matches(write("abd"), 3, ABC_SHA256));
    }

    @Test public void rejectsTruncationAndMissingFile() throws Exception {
        assertFalse(UpdateIntegrity.matches(write("ab"), 3, ABC_SHA256));
        assertFalse(UpdateIntegrity.matches(new File(temporary.getRoot(), "missing.apk"), 3, ABC_SHA256));
    }
}
