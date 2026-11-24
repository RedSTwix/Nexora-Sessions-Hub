package com.rafael.groksessions;

import android.content.Context;
import android.content.SharedPreferences;

/** Native profile preferences, independent of a site's cookies and storage. */
final class SiteAssistantSettings {
    static final String LIMIT_PREFIX = "assistant_vidogrid_limit_";
    static final String QUEUE_PREFIX = "assistant_vidogrid_queue_";
    static final int DEFAULT_LIMIT = 5;
    static final int DEFAULT_QUEUE_SIZE = 2;
    static final int MAX_QUEUE_SIZE = 10;
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

    int queueSize(String profileId) {
        int value = preferences.getInt(queueKey(profileId), DEFAULT_QUEUE_SIZE);
        return value >= 0 && value <= MAX_QUEUE_SIZE ? value : DEFAULT_QUEUE_SIZE;
    }

    boolean save(String profileId, int limit, int queueSize) {
        return limit >= 0 && queueSize >= 0 && queueSize <= MAX_QUEUE_SIZE
                && preferences.edit().putInt(key(profileId), limit).putInt(queueKey(profileId), queueSize).commit();
    }

    static boolean isPreferenceKey(String key) {
        return key.startsWith(LIMIT_PREFIX) || key.startsWith(QUEUE_PREFIX);
    }

    static String key(String profileId) { return LIMIT_PREFIX + profileId; }
    static String queueKey(String profileId) { return QUEUE_PREFIX + profileId; }
}
