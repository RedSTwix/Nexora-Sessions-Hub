package com.rafael.groksessions;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

final class SiteShortcutRepository {
    private final SharedPreferences preferences;
    private final String key;

    SiteShortcutRepository(Context context, String profileId) {
        preferences = context.getSharedPreferences("account_manager", Context.MODE_PRIVATE);
        key = preferenceKey(profileId);
    }

    static String preferenceKey(String profileId) {
        if (profileId == null || profileId.isEmpty()) throw new IllegalArgumentException("Perfil inválido.");
        return "profile_shortcuts_" + profileId;
    }

    List<SiteShortcut> getAll() {
        List<SiteShortcut> result = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(preferences.getString(key, "[]"));
            for (int i = 0; i < values.length(); i++) {
                try { result.add(SiteShortcut.fromJson(values.getJSONObject(i))); }
                catch (JSONException | IllegalArgumentException ignored) { /* Skip only the invalid entry. */ }
            }
        } catch (JSONException ignored) { /* Keep the home screen usable with invalid local metadata. */ }
        return result;
    }

    SiteShortcut add(String name, String url, Bitmap icon) {
        List<SiteShortcut> sites = getAll();
        SiteShortcut site = new SiteShortcut(UUID.randomUUID().toString(), name, url, ShortcutIcon.encode(icon));
        requireUniqueUrl(sites, site);
        sites.add(site);
        save(sites);
        return site;
    }

    void edit(String id, String name, String url, Bitmap icon) {
        List<SiteShortcut> sites = getAll();
        for (int i = 0; i < sites.size(); i++) {
            SiteShortcut old = sites.get(i);
            if (!old.id.equals(id)) continue;
            String normalized = SiteShortcut.normalizeUrl(url);
            String image = icon != null ? ShortcutIcon.encode(icon)
                    : SiteShortcut.origin(old.url).equals(SiteShortcut.origin(normalized)) ? old.icon : null;
            SiteShortcut edited = new SiteShortcut(id, name, normalized, image);
            requireUniqueUrl(sites, edited);
            sites.set(i, edited);
            save(sites);
            return;
        }
        throw new IllegalArgumentException("Este atalho foi removido.");
    }

    void remove(String id) {
        List<SiteShortcut> sites = getAll();
        sites.removeIf(site -> site.id.equals(id));
        save(sites);
    }

    void move(String id, int direction) {
        if (direction != -1 && direction != 1) return;
        List<SiteShortcut> sites = getAll();
        for (int i = 0; i < sites.size(); i++) {
            if (sites.get(i).id.equals(id) && i + direction >= 0 && i + direction < sites.size()) {
                Collections.swap(sites, i, i + direction);
                save(sites);
                return;
            }
        }
    }

    void updateIcon(String pageUrl, Bitmap icon) {
        String origin = SiteShortcut.origin(pageUrl);
        if (origin.isEmpty() || icon == null) return;
        List<SiteShortcut> sites = getAll();
        boolean changed = false;
        String encoded = null;
        for (int i = 0; i < sites.size(); i++) {
            SiteShortcut site = sites.get(i);
            if (!origin.equals(SiteShortcut.origin(site.url))) continue;
            if (encoded == null) encoded = ShortcutIcon.encode(icon);
            if (encoded == null) return;
            if (!encoded.equals(site.icon)) {
                sites.set(i, new SiteShortcut(site.id, site.name, site.url, encoded));
                changed = true;
            }
        }
        if (changed) save(sites);
    }

    private static void requireUniqueUrl(List<SiteShortcut> sites, SiteShortcut candidate) {
        for (SiteShortcut site : sites)
            if (!site.id.equals(candidate.id) && site.url.equals(candidate.url))
                throw new IllegalArgumentException("Este endereço já tem um atalho neste perfil.");
    }

    private void save(List<SiteShortcut> sites) {
        JSONArray values = new JSONArray();
        try {
            for (SiteShortcut site : sites) values.put(site.toJson());
            preferences.edit().putString(key, values.toString()).apply();
        } catch (JSONException exception) { throw new IllegalStateException("Não foi possível salvar os atalhos.", exception); }
    }
}
