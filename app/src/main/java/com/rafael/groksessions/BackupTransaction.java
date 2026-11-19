package com.rafael.groksessions;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.IOException;
import org.json.JSONException;
import org.json.JSONObject;

/** Installs only a validated staging directory, preserving a rollback until the commit completes. */
final class BackupTransaction {
    private BackupTransaction() {}
    private static File rollback(Context context) { return new File(context.getNoBackupFilesDir(), "restore-rollback"); }
    static File webview(Context context) { return new File(context.getApplicationInfo().dataDir, "app_webview"); }
    static File preferences(Context context) { return new File(context.getApplicationInfo().dataDir, "shared_prefs"); }

    static void restore(Context context, BackupArchive.Restored backup) throws IOException {
        BackupSettings.validate(backup.settings);
        recover(context);
        File rollback = rollback(context);
        BackupFiles.mkdir(rollback);
        SharedPreferences preferences = context.getSharedPreferences("account_manager", Context.MODE_PRIVATE);
        File target = webview(context);
        File targetPreferences = preferences(context);
        boolean hadWebview = target.exists(), hadPreferences = targetPreferences.exists();
        try {
            File stagedPreferences = new File(backup.stage, "webview_prefs");
            BackupFiles.mkdir(stagedPreferences);
            File[] existingPreferences = targetPreferences.listFiles();
            if (existingPreferences != null) for (File file : existingPreferences) {
                if (file.isFile() && file.getName().startsWith("app_updates.xml")) {
                    try (java.io.InputStream input = new java.io.FileInputStream(file);
                         java.io.OutputStream output = new java.io.FileOutputStream(new File(stagedPreferences, file.getName()))) {
                        byte[] buffer = new byte[32768]; int count;
                        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    }
                }
            }
            BackupFiles.writeJson(new File(rollback, "state.json"), new JSONObject().put("phase", "prepared")
                    .put("had_webview", hadWebview).put("had_preferences", hadPreferences));
            if (target.exists()) BackupFiles.move(target, new File(rollback, "webview"));
            if (targetPreferences.exists()) BackupFiles.move(targetPreferences, new File(rollback, "preferences"));
            BackupFiles.writeJson(new File(rollback, "state.json"), new JSONObject().put("phase", "installing")
                    .put("had_webview", hadWebview).put("had_preferences", hadPreferences));
            BackupFiles.move(backup.webview, target);
            BackupFiles.move(stagedPreferences, targetPreferences);
            BackupSettings.restore(preferences, backup.settings);
            BackupFiles.writeJson(new File(rollback, "state.json"), new JSONObject().put("phase", "committed"));
        } catch (IOException | JSONException exception) {
            try { recover(context); } catch (IOException recovery) { exception.addSuppressed(recovery); }
            if (exception instanceof IOException) throw (IOException) exception;
            throw new IOException("Não foi possível concluir a restauração.", exception);
        }
        // A cleanup failure does not roll back a fully committed restore.
        try { BackupFiles.delete(rollback); } catch (IOException ignored) {}
    }

    static void recover(Context context) throws IOException {
        File rollback = rollback(context);
        if (!rollback.exists()) return;
        File stateFile = new File(rollback, "state.json");
        if (!BackupFiles.jsonExists(stateFile)) { BackupFiles.delete(rollback); return; }
        try {
            JSONObject state = BackupFiles.readJson(stateFile);
            String phase = state.getString("phase");
            if (!phase.equals("committed")) {
                boolean hadWebview = state.optBoolean("had_webview", true);
                boolean hadPreferences = state.optBoolean("had_preferences", true);
                BackupFiles.writeJson(stateFile, new JSONObject().put("phase", "recovering")
                        .put("had_webview", hadWebview).put("had_preferences", hadPreferences));
                recoverDirectory(new File(rollback, "webview"), webview(context), hadWebview, phase);
                recoverDirectory(new File(rollback, "preferences"), preferences(context), hadPreferences, phase);
                BackupFiles.writeJson(stateFile, new JSONObject().put("phase", "committed"));
            }
            BackupFiles.delete(rollback);
        } catch (JSONException exception) { throw new IOException("Não foi possível recuperar a restauração interrompida.", exception); }
    }

    private static void recoverDirectory(File previous, File target, boolean hadPrevious, String phase) throws IOException {
        if (previous.exists()) { BackupFiles.delete(target); BackupFiles.move(previous, target); }
        else if (!hadPrevious && (phase.equals("installing") || phase.equals("recovering"))) BackupFiles.delete(target);
    }
}
