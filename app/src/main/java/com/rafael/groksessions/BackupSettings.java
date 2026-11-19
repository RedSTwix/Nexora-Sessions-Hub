package com.rafael.groksessions;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;

/** Only persistent account data belongs in a backup; pending callbacks/downloads do not. */
final class BackupSettings {
    private static final Set<String> STRINGS = new HashSet<>(Arrays.asList("accounts", "folders", "folder_styles", "account_last_used",
            "account_unavailable_until", "profile_last_used"));
    private static final Set<String> SETS = new HashSet<>(Arrays.asList("pinned_folders", "pinned_accounts", "availability_folders", "profile_availability_folders"));
    private static final Set<String> INTS = new HashSet<>(Arrays.asList("availability_duration_hours", "profile_availability_duration_hours"));
    private BackupSettings() {}

    static JSONObject export(SharedPreferences preferences) throws IOException {
        JSONObject result = new JSONObject();
        try {
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (STRINGS.contains(key) || key.startsWith("profile_shortcuts_")) result.put(key, value);
                else if (SETS.contains(key)) result.put(key, new JSONArray((Set<?>) value));
                else if (INTS.contains(key)) result.put(key, value);
            }
            validate(result);
            return result;
        } catch (JSONException | ClassCastException exception) { throw new IOException("Os dados locais não puderam ser lidos.", exception); }
    }

    static void validate(JSONObject settings) throws IOException {
        try {
            Set<String> folders = new HashSet<>(), accounts = new HashSet<>();
            JSONArray folderList = new JSONArray(settings.optString("folders", "[]"));
            for (int i = 0; i < folderList.length(); i++) {
                JSONObject folder = folderList.getJSONObject(i);
                String id = folder.getString("id");
                requireId(id);
                if (folder.getString("name").trim().isEmpty() || !folders.add(id)) throw new IOException("Pastas inválidas no backup.");
            }
            JSONArray accountList = new JSONArray(settings.optString("accounts", "[]"));
            for (int i = 0; i < accountList.length(); i++) {
                JSONObject account = accountList.getJSONObject(i);
                String id = account.getString("id");
                requireId(id);
                if (!accounts.add(id) || account.getString("name").trim().isEmpty()
                        || !folders.contains(account.getString("folderId"))) throw new IOException("Contas inválidas no backup.");
                if (account.has("privacySeed") && !account.getString("privacySeed").matches("[0-9a-f]{64}"))
                    throw new IOException("Identidade de perfil inválida no backup.");
            }
            Iterator<String> keys = settings.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (STRINGS.contains(key)) { new JSONObject(key.equals("accounts") || key.equals("folders") ? "{}" : settings.getString(key)); }
                else if (SETS.contains(key)) {
                    JSONArray values = settings.getJSONArray(key);
                    for (int i = 0; i < values.length(); i++) requireId(values.getString(i));
                } else if (INTS.contains(key)) {
                    int hours = settings.getInt(key);
                    if (hours < 1 || hours > 720) throw new IOException("Tempo de espera inválido.");
                } else if (key.startsWith("profile_shortcuts_")) {
                    String profile = key.substring("profile_shortcuts_".length());
                    requireId(profile);
                    if (!accounts.contains(profile)) throw new IOException("Atalhos sem perfil no backup.");
                    JSONArray shortcuts = new JSONArray(settings.getString(key));
                    for (int i = 0; i < shortcuts.length(); i++) SiteShortcut.fromJson(shortcuts.getJSONObject(i));
                } else throw new IOException("Configuração desconhecida no backup.");
            }
        } catch (JSONException | IllegalArgumentException exception) { throw new IOException("O backup contém dados inválidos.", exception); }
    }

    private static void requireId(String id) throws IOException {
        if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw new IOException("Identificador inválido no backup.");
    }

    static void restore(SharedPreferences preferences, JSONObject settings) throws IOException {
        validate(settings);
        SharedPreferences.Editor editor = preferences.edit().clear();
        try {
            Iterator<String> keys = settings.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (SETS.contains(key)) {
                    Set<String> values = new HashSet<>();
                    JSONArray items = settings.getJSONArray(key);
                    for (int i = 0; i < items.length(); i++) values.add(items.getString(i));
                    editor.putStringSet(key, values);
                } else if (INTS.contains(key)) editor.putInt(key, settings.getInt(key));
                else editor.putString(key, settings.getString(key));
            }
        } catch (JSONException exception) { throw new IOException("Configurações inválidas.", exception); }
        if (!editor.commit()) throw new IOException("Não foi possível salvar as configurações restauradas.");
    }

    static Map<String, Integer> counts(JSONObject settings) throws IOException {
        try {
            Map<String, Integer> counts = new HashMap<>();
            counts.put("accounts", new JSONArray(settings.optString("accounts", "[]")).length());
            counts.put("folders", new JSONArray(settings.optString("folders", "[]")).length());
            return counts;
        } catch (JSONException exception) { throw new IOException("Dados inválidos.", exception); }
    }
}
