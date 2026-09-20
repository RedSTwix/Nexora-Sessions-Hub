package com.rafael.groksessions;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class AccountRepository {
    private static final String PREFS = "account_manager";
    private static final String ACCOUNTS = "accounts";
    private static final String FOLDERS = "folders";
    private static final String PENDING_DELETIONS = "pending_deletions";
    private static final String PINNED_FOLDERS = "pinned_folders";
    private static final String PINNED_ACCOUNTS = "pinned_accounts";
    private static final String FOLDER_STYLES = "folder_styles";
    private static final String ACCOUNT_UNAVAILABLE_UNTIL = "account_unavailable_until";
    private static final String ACCOUNT_LAST_USED = "account_last_used";
    private static final String AVAILABILITY_FOLDERS = "availability_folders";
    private static final String AVAILABILITY_DURATION_HOURS = "availability_duration_hours";
    private static final int DEFAULT_AVAILABILITY_HOURS = 12;
    private static final String LEGACY_FOLDER_ID = "folder_ccntcache";
    private static final String LEGACY_FOLDER_NAME = "ccntcache";

    private final SharedPreferences preferences;

    AccountRepository(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        migrateAccountsToFolders();
    }

    List<Account> getAll() {
        List<Account> result = new ArrayList<>();
        String raw = preferences.getString(ACCOUNTS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                String id = item.optString("id", "");
                String name = item.optString("name", "");
                String folderId = item.optString("folderId", LEGACY_FOLDER_ID);
                if (!id.isEmpty() && !name.isEmpty()) {
                    result.add(new Account(id, name, folderId));
                }
            }
        } catch (JSONException ignored) {
            // Em caso de dados inválidos, mantém a interface utilizável sem expor dados.
        }
        return result;
    }

    List<Account> getForFolder(String folderId) {
        List<Account> result = new ArrayList<>();
        for (Account account : getAll()) {
            if (account.folderId.equals(folderId)) {
                result.add(account);
            }
        }
        return result;
    }

    Account add(String name, String folderId) {
        Account account = new Account(
                "grok_" + UUID.randomUUID().toString().replace("-", ""),
                name.trim(),
                folderId
        );
        List<Account> accounts = getAll();
        accounts.add(account);
        save(accounts);
        return account;
    }

    List<AccountFolder> getFolders() {
        List<AccountFolder> result = new ArrayList<>();
        String raw = preferences.getString(FOLDERS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                String id = item.optString("id", "");
                String name = item.optString("name", "");
                if (!id.isEmpty() && !name.isEmpty()) {
                    result.add(new AccountFolder(id, name));
                }
            }
        } catch (JSONException ignored) {
            // Mantém a pasta padrão disponível em caso de dados inválidos.
        }
        return result;
    }

    AccountFolder addFolder(String name) {
        AccountFolder folder = new AccountFolder(
                "folder_" + UUID.randomUUID().toString().replace("-", ""),
                name.trim()
        );
        List<AccountFolder> folders = getFolders();
        folders.add(folder);
        saveFolders(folders);
        if (preferences.contains(AVAILABILITY_FOLDERS)) {
            Set<String> enabled = getAvailabilityEnabledFolderIds();
            enabled.add(folder.id);
            saveStringSet(AVAILABILITY_FOLDERS, enabled);
        }
        return folder;
    }

    void remove(String accountId) {
        List<Account> accounts = getAll();
        accounts.removeIf(account -> account.id.equals(accountId));
        save(accounts);
        Set<String> pinned = pinnedAccounts();
        if (pinned.remove(accountId)) {
            saveStringSet(PINNED_ACCOUNTS, pinned);
        }
        removeAccountAvailability(accountId);
    }

    void renameAccount(String accountId, String newName) {
        List<Account> accounts = getAll();
        List<Account> renamed = new ArrayList<>();
        for (Account account : accounts) {
            if (account.id.equals(accountId)) {
                renamed.add(new Account(account.id, newName.trim(), account.folderId));
            } else {
                renamed.add(account);
            }
        }
        save(renamed);
    }

    void renameFolder(String folderId, String newName) {
        List<AccountFolder> folders = getFolders();
        List<AccountFolder> renamed = new ArrayList<>();
        for (AccountFolder folder : folders) {
            if (folder.id.equals(folderId)) {
                renamed.add(new AccountFolder(folder.id, newName.trim()));
            } else {
                renamed.add(folder);
            }
        }
        saveFolders(renamed);
    }

    boolean isFolderPinned(String folderId) {
        return pinnedFolders().contains(folderId);
    }

    boolean isAccountPinned(String accountId) {
        return pinnedAccounts().contains(accountId);
    }

    void toggleFolderPinned(String folderId) {
        Set<String> pinned = pinnedFolders();
        toggleInSet(pinned, folderId);
        saveStringSet(PINNED_FOLDERS, pinned);
        stablePinFolders(pinned);
    }

    void toggleAccountPinned(String accountId) {
        Set<String> pinned = pinnedAccounts();
        toggleInSet(pinned, accountId);
        saveStringSet(PINNED_ACCOUNTS, pinned);
        stablePinAccounts(pinned);
    }

    int getFolderStyle(String folderId) {
        try {
            return new JSONObject(preferences.getString(FOLDER_STYLES, "{}"))
                    .optInt(folderId, 0);
        } catch (JSONException ignored) {
            return 0;
        }
    }

    void setFolderStyle(String folderId, int style) {
        try {
            JSONObject styles = new JSONObject(preferences.getString(FOLDER_STYLES, "{}"));
            styles.put(folderId, Math.max(0, Math.min(3, style)));
            preferences.edit().putString(FOLDER_STYLES, styles.toString()).apply();
        } catch (JSONException ignored) {
            // Mantém a personalização anterior se o JSON local estiver inválido.
        }
    }

    boolean canMoveFolders(Set<String> folderIds, int direction) {
        List<AccountFolder> folders = getFolders();
        if (direction < 0) {
            for (int index = 1; index < folders.size(); index++) {
                if (folderIds.contains(folders.get(index).id)
                        && !folderIds.contains(folders.get(index - 1).id)) {
                    return true;
                }
            }
        } else {
            for (int index = folders.size() - 2; index >= 0; index--) {
                if (folderIds.contains(folders.get(index).id)
                        && !folderIds.contains(folders.get(index + 1).id)) {
                    return true;
                }
            }
        }
        return false;
    }

    void moveFolders(Set<String> folderIds, int direction) {
        List<AccountFolder> folders = getFolders();
        if (direction < 0) {
            for (int index = 1; index < folders.size(); index++) {
                if (folderIds.contains(folders.get(index).id)
                        && !folderIds.contains(folders.get(index - 1).id)) {
                    AccountFolder previous = folders.get(index - 1);
                    folders.set(index - 1, folders.get(index));
                    folders.set(index, previous);
                }
            }
        } else {
            for (int index = folders.size() - 2; index >= 0; index--) {
                if (folderIds.contains(folders.get(index).id)
                        && !folderIds.contains(folders.get(index + 1).id)) {
                    AccountFolder next = folders.get(index + 1);
                    folders.set(index + 1, folders.get(index));
                    folders.set(index, next);
                }
            }
        }
        saveFolders(folders);
    }

    boolean canMoveAccounts(Set<String> accountIds, String folderId, int direction) {
        List<Account> folderAccounts = getForFolder(folderId);
        if (direction < 0) {
            for (int index = 1; index < folderAccounts.size(); index++) {
                if (accountIds.contains(folderAccounts.get(index).id)
                        && !accountIds.contains(folderAccounts.get(index - 1).id)) {
                    return true;
                }
            }
        } else {
            for (int index = folderAccounts.size() - 2; index >= 0; index--) {
                if (accountIds.contains(folderAccounts.get(index).id)
                        && !accountIds.contains(folderAccounts.get(index + 1).id)) {
                    return true;
                }
            }
        }
        return false;
    }

    void moveAccountsWithinFolder(Set<String> accountIds, String folderId, int direction) {
        List<Account> folderAccounts = getForFolder(folderId);
        if (direction < 0) {
            for (int index = 1; index < folderAccounts.size(); index++) {
                if (accountIds.contains(folderAccounts.get(index).id)
                        && !accountIds.contains(folderAccounts.get(index - 1).id)) {
                    Account previous = folderAccounts.get(index - 1);
                    folderAccounts.set(index - 1, folderAccounts.get(index));
                    folderAccounts.set(index, previous);
                }
            }
        } else {
            for (int index = folderAccounts.size() - 2; index >= 0; index--) {
                if (accountIds.contains(folderAccounts.get(index).id)
                        && !accountIds.contains(folderAccounts.get(index + 1).id)) {
                    Account next = folderAccounts.get(index + 1);
                    folderAccounts.set(index + 1, folderAccounts.get(index));
                    folderAccounts.set(index, next);
                }
            }
        }

        List<Account> accounts = getAll();
        int folderIndex = 0;
        for (int index = 0; index < accounts.size(); index++) {
            if (accounts.get(index).folderId.equals(folderId)) {
                accounts.set(index, folderAccounts.get(folderIndex++));
            }
        }
        save(accounts);
    }

    void moveAccounts(Set<String> accountIds, String targetFolderId) {
        List<Account> accounts = getAll();
        List<Account> moved = new ArrayList<>();
        for (Account account : accounts) {
            if (accountIds.contains(account.id)) {
                moved.add(new Account(account.id, account.name, targetFolderId));
            } else {
                moved.add(account);
            }
        }
        save(moved);
    }

    boolean removeFolder(String folderId) {
        if (!getForFolder(folderId).isEmpty()) {
            return false;
        }
        List<AccountFolder> folders = getFolders();
        boolean removed = folders.removeIf(folder -> folder.id.equals(folderId));
        if (!removed) {
            return false;
        }
        saveFolders(folders);
        Set<String> pinned = pinnedFolders();
        pinned.remove(folderId);
        saveStringSet(PINNED_FOLDERS, pinned);
        if (preferences.contains(AVAILABILITY_FOLDERS)) {
            Set<String> enabled = getAvailabilityEnabledFolderIds();
            enabled.remove(folderId);
            saveStringSet(AVAILABILITY_FOLDERS, enabled);
        }
        try {
            JSONObject styles = new JSONObject(preferences.getString(FOLDER_STYLES, "{}"));
            styles.remove(folderId);
            preferences.edit().putString(FOLDER_STYLES, styles.toString()).apply();
        } catch (JSONException ignored) {
            // A remoção da pasta continua válida mesmo sem personalização legível.
        }
        return true;
    }

    void markForDeletion(String profileId) {
        Set<String> ids = getPendingDeletions();
        ids.add(profileId);
        preferences.edit().putStringSet(PENDING_DELETIONS, ids).apply();
    }

    void deletionCompleted(String profileId) {
        Set<String> ids = getPendingDeletions();
        ids.remove(profileId);
        preferences.edit().putStringSet(PENDING_DELETIONS, ids).apply();
    }

    void markAccountUnavailable(Account account) {
        if (!isAvailabilityEnabledForFolder(account.folderId)) {
            return;
        }
        try {
            JSONObject lastUsed = lastUsed();
            lastUsed.put(account.id, System.currentTimeMillis());
            preferences.edit().putString(ACCOUNT_LAST_USED, lastUsed.toString()).apply();
        } catch (JSONException ignored) {
            // A abertura da conta continua funcionando mesmo se o indicador não puder ser salvo.
        }
    }

    boolean isAccountAvailable(String accountId) {
        try {
            long usedAt = lastUsed().optLong(accountId, 0L);
            long duration = getAvailabilityDurationHours() * 60L * 60L * 1000L;
            return usedAt <= 0L || System.currentTimeMillis() - usedAt >= duration;
        } catch (JSONException ignored) {
            return true;
        }
    }

    boolean isAvailabilityEnabledForFolder(String folderId) {
        return getAvailabilityEnabledFolderIds().contains(folderId);
    }

    Set<String> getAvailabilityEnabledFolderIds() {
        if (preferences.contains(AVAILABILITY_FOLDERS)) {
            return new HashSet<>(preferences.getStringSet(AVAILABILITY_FOLDERS, new HashSet<>()));
        }
        Set<String> allFolders = new HashSet<>();
        for (AccountFolder folder : getFolders()) {
            allFolders.add(folder.id);
        }
        return allFolders;
    }

    int getAvailabilityDurationHours() {
        return Math.max(1, preferences.getInt(AVAILABILITY_DURATION_HOURS, DEFAULT_AVAILABILITY_HOURS));
    }

    void saveAvailabilitySettings(Set<String> folderIds, int durationHours) {
        preferences.edit()
                .putStringSet(AVAILABILITY_FOLDERS, new HashSet<>(folderIds))
                .putInt(AVAILABILITY_DURATION_HOURS, Math.max(1, Math.min(720, durationHours)))
                .apply();
    }

    void resetAvailabilityIndicators() {
        preferences.edit()
                .remove(ACCOUNT_LAST_USED)
                .remove(ACCOUNT_UNAVAILABLE_UNTIL)
                .apply();
    }

    boolean hasAvailableAccount(String folderId) {
        List<Account> accounts = getForFolder(folderId);
        if (accounts.isEmpty()) {
            return true;
        }
        for (Account account : accounts) {
            if (isAccountAvailable(account.id)) {
                return true;
            }
        }
        return false;
    }

    Set<String> getPendingDeletions() {
        return new HashSet<>(preferences.getStringSet(PENDING_DELETIONS, new HashSet<>()));
    }

    private Set<String> pinnedFolders() {
        return new HashSet<>(preferences.getStringSet(PINNED_FOLDERS, new HashSet<>()));
    }

    private JSONObject lastUsed() throws JSONException {
        String raw = preferences.getString(ACCOUNT_LAST_USED, null);
        if (raw != null) {
            return new JSONObject(raw);
        }
        JSONObject migrated = new JSONObject();
        JSONObject legacy = new JSONObject(preferences.getString(ACCOUNT_UNAVAILABLE_UNTIL, "{}"));
        long legacyDuration = DEFAULT_AVAILABILITY_HOURS * 60L * 60L * 1000L;
        java.util.Iterator<String> keys = legacy.keys();
        while (keys.hasNext()) {
            String accountId = keys.next();
            migrated.put(accountId, Math.max(0L, legacy.optLong(accountId, 0L) - legacyDuration));
        }
        preferences.edit().putString(ACCOUNT_LAST_USED, migrated.toString()).apply();
        return migrated;
    }

    private void removeAccountAvailability(String accountId) {
        try {
            JSONObject lastUsed = lastUsed();
            lastUsed.remove(accountId);
            preferences.edit().putString(ACCOUNT_LAST_USED, lastUsed.toString()).apply();
        } catch (JSONException ignored) {
            // A remoção da conta não depende do metadado visual de disponibilidade.
        }
    }

    private Set<String> pinnedAccounts() {
        return new HashSet<>(preferences.getStringSet(PINNED_ACCOUNTS, new HashSet<>()));
    }

    private void saveStringSet(String key, Set<String> values) {
        preferences.edit().putStringSet(key, new HashSet<>(values)).apply();
    }

    private void toggleInSet(Set<String> values, String id) {
        if (!values.remove(id)) {
            values.add(id);
        }
    }

    private void stablePinFolders(Set<String> pinned) {
        List<AccountFolder> folders = getFolders();
        List<AccountFolder> ordered = new ArrayList<>();
        for (AccountFolder folder : folders) {
            if (pinned.contains(folder.id)) ordered.add(folder);
        }
        for (AccountFolder folder : folders) {
            if (!pinned.contains(folder.id)) ordered.add(folder);
        }
        saveFolders(ordered);
    }

    private void stablePinAccounts(Set<String> pinned) {
        List<Account> accounts = getAll();
        List<Account> ordered = new ArrayList<>();
        for (Account account : accounts) {
            if (pinned.contains(account.id)) ordered.add(account);
        }
        for (Account account : accounts) {
            if (!pinned.contains(account.id)) ordered.add(account);
        }
        save(ordered);
    }

    private int indexOfFolder(List<AccountFolder> folders, String id) {
        for (int i = 0; i < folders.size(); i++) {
            if (folders.get(i).id.equals(id)) return i;
        }
        return -1;
    }

    private int indexOfAccount(List<Account> accounts, String id) {
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).id.equals(id)) return i;
        }
        return -1;
    }

    private void save(List<Account> accounts) {
        JSONArray array = new JSONArray();
        for (Account account : accounts) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", account.id);
                item.put("name", account.name);
                item.put("folderId", account.folderId);
                array.put(item);
            } catch (JSONException ignored) {
                // Strings simples não devem falhar ao serem serializadas.
            }
        }
        preferences.edit().putString(ACCOUNTS, array.toString()).apply();
    }

    private void migrateAccountsToFolders() {
        String raw = preferences.getString(ACCOUNTS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            boolean changed = false;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                if (!item.has("folderId")) {
                    item.put("folderId", LEGACY_FOLDER_ID);
                    changed = true;
                }
            }
            if (changed) {
                preferences.edit().putString(ACCOUNTS, array.toString()).apply();
                List<AccountFolder> folders = getFolders();
                boolean legacyFolderExists = false;
                for (AccountFolder folder : folders) {
                    if (folder.id.equals(LEGACY_FOLDER_ID)) {
                        legacyFolderExists = true;
                        break;
                    }
                }
                if (!legacyFolderExists) {
                    folders.add(0, new AccountFolder(LEGACY_FOLDER_ID, LEGACY_FOLDER_NAME));
                    saveFolders(folders);
                }
            }
        } catch (JSONException ignored) {
            // A leitura normal tratará dados inválidos sem encerrar o aplicativo.
        }
    }

    private void saveFolders(List<AccountFolder> folders) {
        JSONArray array = new JSONArray();
        for (AccountFolder folder : folders) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", folder.id);
                item.put("name", folder.name);
                array.put(item);
            } catch (JSONException ignored) {
                // Strings simples não devem falhar ao serem serializadas.
            }
        }
        preferences.edit().putString(FOLDERS, array.toString()).apply();
    }
}
