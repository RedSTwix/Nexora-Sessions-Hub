package com.rafael.groksessions;

import java.security.SecureRandom;

/** Created only when adding an account, never as a side effect of opening an old one. */
final class ProfileIdentity {
    private ProfileIdentity() {}

    static String newSeed() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder(64);
        for (byte value : bytes) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        return hex.toString();
    }
}
