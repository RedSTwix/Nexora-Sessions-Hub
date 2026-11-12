package com.rafael.groksessions;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class UpdateIntegrity {
    private UpdateIntegrity() {}

    static boolean matches(File apk, long expectedSize, String expectedSha256) throws IOException {
        if (!apk.isFile() || apk.length() != expectedSize) {
            return false;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream stream = new FileInputStream(apk)) {
                byte[] buffer = new byte[32768];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) {
                hex.append(Character.forDigit((value >>> 4) & 15, 16));
                hex.append(Character.forDigit(value & 15, 16));
            }
            return hex.toString().equalsIgnoreCase(expectedSha256);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
