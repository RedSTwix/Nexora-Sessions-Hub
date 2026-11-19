package com.rafael.groksessions;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import java.io.File;
import java.io.IOException;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

final class BackupCoordinator {
    private BackupCoordinator() {}
    private static File marker(Context context) { return new File(context.getNoBackupFilesDir(), "backup-running.json"); }

    static boolean isActive(Context context) {
        File marker = marker(context);
        if (!marker.exists()) return false;
        try {
            int pid = BackupFiles.readJson(marker).getInt("pid");
            try (java.io.InputStream input = new java.io.FileInputStream("/proc/" + pid + "/cmdline")) {
                byte[] name = new byte[256];
                int length = input.read(name), end = 0;
                while (end < length && name[end] != 0) end++;
                if ((context.getPackageName() + ":backup").equals(new String(name, 0, end, java.nio.charset.StandardCharsets.UTF_8))) return true;
            } catch (IOException ignored) { /* Fall back to Android's own-process list. */ }
            for (ActivityManager.RunningAppProcessInfo process : processes(context))
                if (process.pid == pid && process.uid == Process.myUid() && process.processName.equals(context.getPackageName() + ":backup")) return true;
        } catch (IOException | JSONException ignored) { /* A dead worker must not leave the app blocked. */ }
        marker.delete();
        return false;
    }

    static boolean redirectIfBusy(Activity activity) {
        if (!isActive(activity)) return false;
        activity.startActivity(new Intent(activity, BackupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        activity.finish();
        return true;
    }

    static void begin(Context context) throws IOException {
        List<ActivityManager.RunningAppProcessInfo> running = processes(context);
        boolean ownWorker = false;
        for (ActivityManager.RunningAppProcessInfo process : running)
            if (process.pid == Process.myPid() && process.uid == Process.myUid()
                    && process.processName.equals(context.getPackageName() + ":backup")) ownWorker = true;
        if (!ownWorker) throw new IOException("Não foi possível isolar o navegador para copiar os perfis.");
        try { BackupFiles.writeJson(marker(context), new JSONObject().put("pid", Process.myPid())); }
        catch (JSONException exception) { throw new IOException(exception); }
        for (ActivityManager.RunningAppProcessInfo process : running) {
            if (process.uid == Process.myUid() && process.processName.equals(context.getPackageName())) {
                Process.killProcess(process.pid);
                long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
                while (exists(context, process.pid)) {
                    if (android.os.SystemClock.elapsedRealtime() > deadline) throw new IOException("O navegador ainda está aberto. Tente novamente.");
                    try { Thread.sleep(40); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IOException("Operação interrompida.", exception); }
                }
            }
        }
    }

    static void reserve(Context context) throws IOException {
        try { BackupFiles.writeJson(marker(context), new JSONObject().put("pid", Process.myPid())); }
        catch (JSONException exception) { throw new IOException(exception); }
    }

    static void discardAbandonedStages(Context context) {
        File base = context.getNoBackupFilesDir();
        File staging = new File(base, "backup-staging");
        if (staging.exists()) staging.renameTo(new File(base, "backup-abandoned-" + java.util.UUID.randomUUID()));
        File[] abandoned = base.listFiles((directory, name) -> name.startsWith("backup-abandoned-"));
        if (abandoned != null && abandoned.length > 0) new Thread(() -> {
            for (File file : abandoned) try { BackupFiles.delete(file); } catch (IOException ignored) {}
        }, "nexora-backup-cleanup").start();
    }

    private static boolean exists(Context context, int pid) {
        for (ActivityManager.RunningAppProcessInfo process : processes(context)) if (process.pid == pid) return true;
        return false;
    }

    private static List<ActivityManager.RunningAppProcessInfo> processes(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> result = manager == null ? null : manager.getRunningAppProcesses();
        return result == null ? java.util.Collections.emptyList() : result;
    }

    static void end(Context context) { marker(context).delete(); }
}
