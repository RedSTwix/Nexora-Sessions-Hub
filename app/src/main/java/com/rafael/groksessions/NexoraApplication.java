package com.rafael.groksessions;

import android.app.Application;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.webkit.WebView;
import java.io.IOException;
import java.util.List;

public final class NexoraApplication extends Application {
    static volatile boolean recoveryFailed;
    @Override public void onCreate() {
        super.onCreate();
        String process = null;
        if (Build.VERSION.SDK_INT >= 28) process = getProcessName();
        else {
            ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> processes = manager == null ? null : manager.getRunningAppProcesses();
            if (processes != null) for (ActivityManager.RunningAppProcessInfo item : processes) if (item.pid == Process.myPid()) process = item.processName;
        }
        if ((getPackageName() + ":backup").equals(process)) {
            if (Build.VERSION.SDK_INT >= 28) WebView.disableWebView();
            return;
        }
        if (!BackupCoordinator.isActive(this)) {
            try { BackupTransaction.recover(this); }
            catch (IOException exception) { recoveryFailed = true; }
            if (!recoveryFailed) BackupCoordinator.discardAbandonedStages(this);
        }
    }
}
