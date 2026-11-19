package com.rafael.groksessions;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;
import static com.rafael.groksessions.AvailabilityMode.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public final class BackupTest {
    private Context context;
    private SharedPreferences preferences;
    private AccountRepository accounts;
    private Account account;
    private File root, prefs, workspace;
    private JSONObject info;

    @Before public void prepare() throws Exception {
        context = RuntimeEnvironment.getApplication();
        preferences = context.getSharedPreferences("account_manager", 0);
        preferences.edit().clear().commit();
        accounts = new AccountRepository(context);
        AccountFolder folder = accounts.addFolder("Pasta original");
        account = accounts.add("Conta original", folder.id);
        accounts.saveAvailabilitySettings(Collections.singleton(folder.id), 7, OPEN_WITH);
        accounts.saveAvailabilitySettings(Collections.singleton(folder.id), 13, PROFILE_USED);
        accounts.markAccountUnavailable(account, PROFILE_USED);
        accounts.toggleAccountPinned(account.id);
        new SiteShortcutRepository(context, account.id).add("Meu site", "https://example.test/", null);
        root = BackupTransaction.webview(context);
        if (root.exists()) BackupFiles.delete(root);
        BackupFiles.mkdir(root);
        prefs = BackupTransaction.preferences(context);
        BackupFiles.mkdir(prefs);
        write(new File(prefs, "WebViewChromiumPrefs.xml"), "<map><string name=\"profile\">native-profile</string></map>".getBytes(StandardCharsets.UTF_8));
        workspace = new File(context.getCacheDir(), "backup-tests");
        if (workspace.exists()) BackupFiles.delete(workspace);
        BackupFiles.mkdir(workspace);
        info = new JSONObject().put("app_id", context.getPackageName()).put("app_version", "0.8.1")
                .put("version_code", 43).put("sdk", 28).put("webview_package", "com.google.android.webview")
                .put("webview_version", "156.0.1.2").put("created_at", 1700000000000L);
    }

    @After public void finish() throws Exception {
        File rollback = new File(context.getNoBackupFilesDir(), "restore-rollback");
        if (rollback.exists()) BackupFiles.delete(rollback);
        if (workspace.exists()) BackupFiles.delete(workspace);
        if (root.exists()) BackupFiles.delete(root);
    }

    private byte[] exportZip() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BackupArchive.write(output, BackupSettings.export(preferences), new JSONObject(info.toString()), root, prefs, (bytes, files) -> {});
        return output.toByteArray();
    }
    private BackupArchive.Restored read(byte[] zip) throws Exception {
        return BackupArchive.read(new ByteArrayInputStream(zip), workspace, context.getPackageName(), 43, (bytes, files) -> {});
    }

    @Test public void fullZipRestoresCookieDatabaseStorageIdentityFoldersShortcutsAndSeparateIndicators() throws Exception {
        File cookies = new File(root, "Profile_1/Network/Cookies");
        BackupFiles.mkdir(cookies.getParentFile());
        try (SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(cookies, null)) {
            database.execSQL("CREATE TABLE cookies(host_key TEXT,name TEXT,value TEXT,encrypted_value BLOB,expires_utc INTEGER,is_httponly INTEGER)");
            database.execSQL("INSERT INTO cookies VALUES('.example.test','session','fixture-session',X'0102FF',2000000000000000,1)");
        }
        byte[] storage = new byte[]{1,4,9,16,25,0,(byte)255};
        write(new File(root, "Profile_1/Local Storage/leveldb/000003.ldb"), storage);
        write(new File(root, "Profile_1/IndexedDB/site/000004.log"), new byte[]{2,3,5,7});
        BackupFiles.mkdir(new File(root, "Profile_1/Service Worker/Empty"));
        write(new File(root, "webview_data.lock"), new byte[]{0});
        String originalSeed = account.privacySeed;
        byte[] archive = exportZip();
        assertEquals('P', archive[0]); assertEquals('K', archive[1]);
        BackupArchive.Restored restored = read(archive);
        assertFalse(new File(restored.webview, "webview_data.lock").exists());
        accounts.renameAccount(account.id, "Alterado depois do backup");
        write(new File(root, "new-file"), new byte[]{10});
        BackupTransaction.restore(context, restored);
        AccountRepository reopened = new AccountRepository(context);
        assertEquals("Conta original", reopened.getAll().get(0).name);
        assertEquals(originalSeed, reopened.getAll().get(0).privacySeed);
        assertEquals("Pasta original", reopened.getFolders().get(0).name);
        assertEquals("Meu site", new SiteShortcutRepository(context, account.id).getAll().get(0).name);
        assertEquals(7, reopened.getAvailabilityDurationHours(OPEN_WITH));
        assertEquals(13, reopened.getAvailabilityDurationHours(PROFILE_USED));
        assertFalse(reopened.isAccountAvailable(account.id, PROFILE_USED));
        assertTrue(reopened.isAccountAvailable(account.id, OPEN_WITH));
        assertTrue(reopened.isAccountPinned(account.id));
        assertArrayEquals(storage, bytes(new File(root, "Profile_1/Local Storage/leveldb/000003.ldb")));
        assertTrue(new File(root, "Profile_1/Service Worker/Empty").isDirectory());
        assertFalse(new File(root, "new-file").exists());
        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(cookies.getPath(), null, SQLiteDatabase.OPEN_READONLY);
             Cursor cursor = database.rawQuery("SELECT value,encrypted_value,is_httponly,expires_utc FROM cookies", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals("fixture-session", cursor.getString(0));
            assertArrayEquals(new byte[]{1,2,(byte)255}, cursor.getBlob(1));
            assertEquals(1, cursor.getInt(2));
            assertEquals(2000000000000000L, cursor.getLong(3));
        }
        assertTrue(new File(prefs, "WebViewChromiumPrefs.xml").exists());
    }

    @Test public void archiveUsesAFileInventoryAndRejectsChangedOrMissingPayloadsBeforeRestore() throws Exception {
        write(new File(root, "Profile/Cookies"), "original-cookie".getBytes(StandardCharsets.UTF_8));
        byte[] valid = exportZip();
        ByteArrayOutputStream modified = new ByteArrayOutputStream();
        try (java.util.zip.ZipInputStream input = new java.util.zip.ZipInputStream(new ByteArrayInputStream(valid));
             ZipOutputStream output = new ZipOutputStream(modified)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                output.putNextEntry(new ZipEntry(entry.getName()));
                byte[] data = BackupFiles.read(input, 1024 * 1024);
                output.write(entry.getName().endsWith("Cookies") ? "changed".getBytes(StandardCharsets.UTF_8) : data);
                output.closeEntry();
            }
        }
        assertThrows(IOException.class, () -> read(modified.toByteArray()));
        assertEquals("original-cookie", new String(bytes(new File(root, "Profile/Cookies")), StandardCharsets.UTF_8));
        assertEquals("Conta original", accounts.getAll().get(0).name);
        assertEquals(0, workspace.listFiles().length);
    }

    @Test public void rejectsPathTraversalForeignPackagesAndNewerFormatsWithoutReplacingData() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("webview/../../escape")); zip.write(1); zip.closeEntry();
        }
        assertThrows(IOException.class, () -> read(output.toByteArray()));
        info.put("app_id", "other.application");
        byte[] foreign = exportZip();
        assertThrows(IOException.class, () -> read(foreign));
        info.put("app_id", context.getPackageName()).put("version_code", 999);
        byte[] future = exportZip();
        assertThrows(IOException.class, () -> read(future));
        assertEquals("Conta original", accounts.getAll().get(0).name);
    }

    @Test public void pendingCallbacksDownloadsAndCleanupQueuesAreNotRestoredAsAccountData() throws Exception {
        preferences.edit().putStringSet("pending_deletions", Collections.singleton("removed_profile")).commit();
        context.getSharedPreferences("app_updates", 0).edit().putString("state", "target-only").commit();
        write(new File(prefs, "encrypted_auth_sessions.xml"), "private callback fixture".getBytes(StandardCharsets.UTF_8));
        BackupArchive.Restored restored = read(exportZip());
        assertFalse(restored.settings.has("pending_deletions"));
        assertFalse(new File(restored.stage, "webview_prefs/encrypted_auth_sessions.xml").exists());
        assertFalse(new File(restored.stage, "webview_prefs/app_updates.xml").exists());
    }

    @Test public void interruptedInstallRecoversOriginalDirectoriesAndRecoveryIsRepeatable() throws Exception {
        write(new File(root, "original-cookie"), new byte[]{5,6,7});
        File rollback = new File(context.getNoBackupFilesDir(), "restore-rollback");
        BackupFiles.mkdir(rollback);
        BackupFiles.move(root, new File(rollback, "webview"));
        BackupFiles.move(prefs, new File(rollback, "preferences"));
        write(new File(root, "uncommitted-cookie"), new byte[]{9});
        BackupFiles.mkdir(prefs);
        BackupFiles.writeJson(new File(rollback, "state.json"), new JSONObject().put("phase", "installing")
                .put("had_webview", true).put("had_preferences", true));
        BackupTransaction.recover(context);
        assertArrayEquals(new byte[]{5,6,7}, bytes(new File(root, "original-cookie")));
        assertFalse(new File(root, "uncommitted-cookie").exists());
        assertTrue(new File(prefs, "WebViewChromiumPrefs.xml").exists());
        BackupTransaction.recover(context);
        assertTrue(new File(root, "original-cookie").exists());
    }

    @Test public void interruptionDuringRollbackDoesNotDeleteTheAlreadyRecoveredOriginal() throws Exception {
        write(new File(root, "original-cookie"), new byte[]{5});
        File rollback = new File(context.getNoBackupFilesDir(), "restore-rollback");
        BackupFiles.mkdir(rollback);
        BackupFiles.move(prefs, new File(rollback, "preferences"));
        BackupFiles.writeJson(new File(rollback, "state.json"), new JSONObject().put("phase", "recovering")
                .put("had_webview", true).put("had_preferences", true));
        BackupTransaction.recover(context);
        assertArrayEquals(new byte[]{5}, bytes(new File(root, "original-cookie")));
        assertTrue(new File(prefs, "WebViewChromiumPrefs.xml").exists());
    }

    @Test public void validatesMetadataAndWebViewCompatibilityBeforeAcceptingRestore() throws Exception {
        JSONObject settings = BackupSettings.export(preferences);
        settings.put("accounts", new JSONArray().put(new JSONObject().put("id", "../bad").put("name", "Bad").put("folderId", account.folderId)).toString());
        assertThrows(IOException.class, () -> BackupSettings.validate(settings));
        JSONObject saved = new JSONObject(info.toString()).put("has_webview", true);
        JSONObject target = new JSONObject(info.toString()).put("webview_version", "157.0.0.1");
        BackupActivity.requireCompatible(saved, target);
        target.put("webview_version", "155.0.0.1");
        assertThrows(IOException.class, () -> BackupActivity.requireCompatible(saved, target));
        assertEquals(0, BackupActivity.compareVersions("156.1", "156.1.0.0"));
    }

    @Test public void failedInstallRollsBackBothBrowserDataAndCurrentPreferences() throws Exception {
        write(new File(root, "Cookies"), new byte[]{1,2,3});
        BackupArchive.Restored restored = read(exportZip());
        accounts.renameAccount(account.id, "Conta atual preservada");
        preferences.edit().commit();
        write(new File(root, "Cookies"), new byte[]{8,9});
        BackupFiles.delete(restored.webview); // Simulate an unavailable staging directory/disk failure.
        assertThrows(IOException.class, () -> BackupTransaction.restore(context, restored));
        assertArrayEquals(new byte[]{8,9}, bytes(new File(root, "Cookies")));
        assertEquals("Conta atual preservada", accounts.getAll().get(0).name);
        assertTrue(new File(prefs, "WebViewChromiumPrefs.xml").exists());
    }

    @Test public void recoveryAfterCommitKeepsRestoredDataAndOnlyDiscardsTheRollback() throws Exception {
        write(new File(root, "new-cookie"), new byte[]{11});
        File rollback = new File(context.getNoBackupFilesDir(), "restore-rollback");
        write(new File(rollback, "webview/old-cookie"), new byte[]{3});
        BackupFiles.writeJson(new File(rollback, "state.json"), new JSONObject().put("phase", "committed"));
        BackupTransaction.recover(context);
        assertArrayEquals(new byte[]{11}, bytes(new File(root, "new-cookie")));
        assertFalse(rollback.exists());
    }

    private static void write(File file, byte[] bytes) throws Exception {
        BackupFiles.mkdir(file.getParentFile());
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
    }
    private static byte[] bytes(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) { return BackupFiles.read(input, 8 * 1024 * 1024); }
    }
}
