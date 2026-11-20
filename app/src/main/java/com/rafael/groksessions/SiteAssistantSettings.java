package com.rafael.groksessions;

import android.content.Context;
import android.content.SharedPreferences;

/** Native profile preferences, independent of a site's cookies and storage. */
final class SiteAssistantSettings {
    static final String LIMIT_PREFIX = "assistant_vidogrid_limit_";
    static final int DEFAULT_LIMIT = 5;
    private final SharedPreferences preferences;

    SiteAssistantSettings(Context context) {
        preferences = context.getSharedPreferences("account_manager", Context.MODE_PRIVATE);
    }

    int acceptedLimit(String profileId) {
        return preferences.getInt(key(profileId), DEFAULT_LIMIT);
    }

    boolean saveAcceptedLimit(String profileId, int limit) {
        return limit >= 0 && preferences.edit().putInt(key(profileId), limit).commit();
    }

    static String key(String profileId) { return LIMIT_PREFIX + profileId; }
}
