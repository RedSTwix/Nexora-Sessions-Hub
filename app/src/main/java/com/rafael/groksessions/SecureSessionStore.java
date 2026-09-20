package com.rafael.groksessions;

import android.content.Context;

import java.security.KeyStore;

final class SecureSessionStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "grok_session_browser_key_v1";
    private static final String PREFS = "encrypted_auth_sessions";

    private SecureSessionStore() {
    }

    static void clearLegacyCallbacks(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS);
            }
        } catch (Exception ignored) {
            // A ausência da chave apenas significa que não há dado antigo a limpar.
        }
    }
}
