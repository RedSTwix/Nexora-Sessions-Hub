package com.rafael.groksessions;

import org.json.JSONException;
import org.json.JSONObject;

final class Account {
    final String id;
    final String name;
    final String folderId;
    final String privacySeed;

    Account(String id, String name, String folderId) {
        this(id, name, folderId, null);
    }

    Account(String id, String name, String folderId, String privacySeed) {
        this.id = id;
        this.name = name;
        this.folderId = folderId;
        this.privacySeed = privacySeed != null && privacySeed.matches("[0-9a-f]{64}") ? privacySeed : null;
    }

    static Account fromJson(JSONObject item, String defaultFolderId) {
        return new Account(item.optString("id", ""), item.optString("name", ""),
                item.optString("folderId", defaultFolderId), item.optString("privacySeed", null));
    }

    JSONObject toJson() throws JSONException {
        JSONObject item = new JSONObject().put("id", id).put("name", name).put("folderId", folderId);
        if (privacySeed != null) {
            item.put("privacySeed", privacySeed);
        }
        return item;
    }

    Account renamed(String newName) {
        return new Account(id, newName.trim(), folderId, privacySeed);
    }

    Account movedTo(String newFolderId) {
        return new Account(id, name, newFolderId, privacySeed);
    }
}
