package com.rafael.groksessions;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

public final class DualAppAutomationService extends AccessibilityService {
    private static final String TAG = "DualAppAutomation";
    private static final String PREFS = "dual_app_automation";
    private static final String KEY_STAGE = "stage";
    private static final String KEY_STARTED_AT = "started_at";
    private static final String KEY_LAST_RESULT = "last_result";

    private static final String STAGE_IDLE = "idle";
    private static final String STAGE_DISABLE = "disable";
    private static final String STAGE_CONFIRM_DISABLE = "confirm_disable";
    private static final String STAGE_WAIT_DISABLED = "wait_disabled";
    private static final String STAGE_ENABLE = "enable";
    private static final String STAGE_WAIT_ENABLED = "wait_enabled";
    private static final String STAGE_CHOOSE_DUAL = "choose_dual";

    private static final String XSPACE_PACKAGE = "com.miui.securitycore";
    private static final String LAUNCHER_PACKAGE = "com.mi.android.globallauncher";
    private static final long TIMEOUT_MILLIS = 90_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastActionAt;

    private final Runnable processWindow = this::processCurrentWindow;

    public static boolean isEnabled(Context context) {
        AccessibilityManager manager = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) {
            return false;
        }
        for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )) {
            if (info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) {
                continue;
            }
            String packageName = info.getResolveInfo().serviceInfo.packageName;
            String className = info.getResolveInfo().serviceInfo.name;
            if (context.getPackageName().equals(packageName)
                    && DualAppAutomationService.class.getName().equals(className)) {
                return true;
            }
        }
        return false;
    }

    public static boolean requestSwitch(Context context) {
        SharedPreferences preferences = preferences(context);
        preferences.edit()
                .putString(KEY_STAGE, STAGE_DISABLE)
                .putLong(KEY_STARTED_AT, System.currentTimeMillis())
                .remove(KEY_LAST_RESULT)
                .apply();
        try {
            openXspaceSettings(context);
            return true;
        } catch (RuntimeException exception) {
            preferences.edit().putString(KEY_STAGE, STAGE_IDLE).apply();
            return false;
        }
    }

    public static String consumeLastResult(Context context) {
        SharedPreferences preferences = preferences(context);
        String result = preferences.getString(KEY_LAST_RESULT, null);
        if (result != null) {
            preferences.edit().remove(KEY_LAST_RESULT).apply();
        }
        return result;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void openXspaceSettings(Context context) {
        Intent intent = new Intent("miui.intent.action.XSPACE_SETTING");
        intent.setComponent(new ComponentName(
                XSPACE_PACKAGE,
                "com.miui.xspace.ui.activity.XSpaceSettingActivity"
        ));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(intent);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        scheduleProcess(250);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (STAGE_IDLE.equals(stage())) {
            return;
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            handleSystemToast(event);
        }
        scheduleProcess(250);
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacks(processWindow);
    }

    private void scheduleProcess(long delayMillis) {
        handler.removeCallbacks(processWindow);
        handler.postDelayed(processWindow, delayMillis);
    }

    private void processCurrentWindow() {
        String stage = stage();
        if (STAGE_IDLE.equals(stage)) {
            return;
        }
        long startedAt = preferences(this).getLong(KEY_STARTED_AT, 0L);
        if (startedAt == 0L || System.currentTimeMillis() - startedAt > TIMEOUT_MILLIS) {
            fail("A automação expirou. Abra o app e tente novamente.");
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            scheduleProcess(500);
            return;
        }
        CharSequence packageName = root.getPackageName();
        if (packageName != null && LAUNCHER_PACKAGE.contentEquals(packageName)) {
            handleLauncher(root, stage);
            scheduleProcess(350);
            return;
        }
        if (packageName == null || !XSPACE_PACKAGE.contentEquals(packageName)) {
            reopenSettingsIfNeeded(stage);
            return;
        }

        if (STAGE_CONFIRM_DISABLE.equals(stage)) {
            handleDisableConfirmation(root);
        } else if (STAGE_CHOOSE_DUAL.equals(stage)
                || STAGE_WAIT_DISABLED.equals(stage)
                || STAGE_WAIT_ENABLED.equals(stage)) {
            reopenSettingsIfNeeded(stage);
        } else {
            handleXspaceList(root, stage);
        }
        scheduleProcess(500);
    }

    private void handleXspaceList(AccessibilityNodeInfo root, String stage) {
        AccessibilityNodeInfo row = findGrokRow(root);
        if (row == null) {
            if (STAGE_WAIT_ENABLED.equals(stage)) {
                clickKnownPositiveButton(root);
            } else if (STAGE_DISABLE.equals(stage) || STAGE_ENABLE.equals(stage)) {
                searchForGrok(root);
            }
            return;
        }

        Boolean enabled = grokToggleState(row);
        if (enabled == null) {
            return;
        }
        if (STAGE_DISABLE.equals(stage)) {
            if (enabled) {
                if (click(row)) {
                    setStage(STAGE_CONFIRM_DISABLE);
                }
            } else {
                beginEnable(row);
            }
            return;
        }
        if (STAGE_ENABLE.equals(stage)) {
            if (enabled) {
                scheduleProcess(350);
                return;
            }
            beginEnable(row);
        }
    }

    private void handleDisableConfirmation(AccessibilityNodeInfo root) {
        boolean expectedDialog = containsText(root, "Desinstalar dual app")
                || containsText(root, "Desinstalar o dual app")
                || containsText(root, "Uninstall dual app");
        if (!expectedDialog) {
            return;
        }
        AccessibilityNodeInfo ok = findExactText(root, "OK");
        if (ok != null && click(ok)) {
            setStage(STAGE_WAIT_DISABLED);
            handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_HOME), 500L);
        }
    }

    private void beginEnable(AccessibilityNodeInfo row) {
        setStage(STAGE_WAIT_ENABLED);
        if (!click(row)) {
            fail("Não foi possível ativar novamente o Grok Dual.");
            return;
        }
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_HOME), 700L);
    }

    private void handleSystemToast(AccessibilityEvent event) {
        CharSequence packageName = event.getPackageName();
        if (packageName == null || !"com.miui.securitycenter".contentEquals(packageName)) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (CharSequence text : event.getText()) {
            if (text != null) {
                builder.append(text).append(' ');
            }
        }
        String message = builder.toString().trim();
        if (message.isEmpty()) {
            return;
        }
        Log.i(TAG, "Toast do HyperOS: " + message);
    }

    private void handleLauncher(AccessibilityNodeInfo root, String stage) {
        AccessibilityNodeInfo grokIcon = findClickableGrokIcon(root);
        if (STAGE_WAIT_DISABLED.equals(stage)) {
            if (grokIcon == null) {
                setStage(STAGE_ENABLE);
                lastActionAt = System.currentTimeMillis();
                performGlobalAction(GLOBAL_ACTION_RECENTS);
            }
            return;
        }
        if (STAGE_ENABLE.equals(stage)) {
            AccessibilityNodeInfo dualAppsCard = findRecentDualAppsCard(root);
            if (dualAppsCard != null) {
                click(dualAppsCard);
            } else if (System.currentTimeMillis() - lastActionAt > 1_000L) {
                lastActionAt = System.currentTimeMillis();
                performGlobalAction(GLOBAL_ACTION_RECENTS);
            }
            return;
        }
        if (STAGE_WAIT_ENABLED.equals(stage) || STAGE_CHOOSE_DUAL.equals(stage)) {
            if (grokIcon != null && click(grokIcon)) {
                complete("Grok Dual redefinido e aberto para escolher outra conta.");
            }
        }
    }

    private void clickKnownPositiveButton(AccessibilityNodeInfo root) {
        for (String label : new String[]{"OK", "Ativar", "Criar", "Turn on", "Create"}) {
            AccessibilityNodeInfo button = findExactText(root, label);
            if (button != null && click(button)) {
                return;
            }
        }
    }

    private void searchForGrok(AccessibilityNodeInfo root) {
        if (System.currentTimeMillis() - lastActionAt < 500L) {
            return;
        }
        List<AccessibilityNodeInfo> inputs = root.findAccessibilityNodeInfosByViewId("android:id/input");
        for (AccessibilityNodeInfo input : inputs) {
            if (!input.isEditable()) {
                continue;
            }
            CharSequence currentText = input.getText();
            if (currentText != null && "Grok".contentEquals(currentText)) {
                return;
            }
            Bundle arguments = new Bundle();
            arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    "Grok"
            );
            if (input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                lastActionAt = System.currentTimeMillis();
                return;
            }
        }
        List<AccessibilityNodeInfo> searchViews = root.findAccessibilityNodeInfosByViewId(
                XSPACE_PACKAGE + ":id/search_view"
        );
        if (!searchViews.isEmpty() && click(searchViews.get(0))) {
            scheduleProcess(250);
        }
    }

    private AccessibilityNodeInfo findGrokRow(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("Grok");
        for (AccessibilityNodeInfo node : nodes) {
            if (!"Grok".contentEquals(node.getText())) {
                continue;
            }
            AccessibilityNodeInfo current = node;
            for (int depth = 0; current != null && depth < 6; depth++) {
                if (current.isClickable()) {
                    return current;
                }
                current = current.getParent();
            }
        }
        return null;
    }

    private Boolean grokToggleState(AccessibilityNodeInfo row) {
        List<AccessibilityNodeInfo> toggles = row.findAccessibilityNodeInfosByViewId(
                XSPACE_PACKAGE + ":id/sliding_button"
        );
        if (toggles.isEmpty()) {
            return null;
        }
        return toggles.get(0).isChecked();
    }

    private void reopenSettingsIfNeeded(String stage) {
        if (STAGE_CHOOSE_DUAL.equals(stage)
                || STAGE_WAIT_DISABLED.equals(stage)
                || STAGE_WAIT_ENABLED.equals(stage)) {
            if (System.currentTimeMillis() - lastActionAt > 1_500L) {
                lastActionAt = System.currentTimeMillis();
                performGlobalAction(GLOBAL_ACTION_HOME);
            }
            scheduleProcess(600);
            return;
        }
        if (STAGE_ENABLE.equals(stage)) {
            if (System.currentTimeMillis() - lastActionAt > 1_000L) {
                lastActionAt = System.currentTimeMillis();
                performGlobalAction(GLOBAL_ACTION_RECENTS);
            }
            scheduleProcess(500);
            return;
        }
        if (System.currentTimeMillis() - lastActionAt > 1_500L) {
            lastActionAt = System.currentTimeMillis();
            openXspaceSettings(this);
        }
        scheduleProcess(600);
    }

    private AccessibilityNodeInfo findClickableGrokIcon(AccessibilityNodeInfo node) {
        CharSequence description = node.getContentDescription();
        if (node.isClickable() && description != null
                && "Grok".equalsIgnoreCase(description.toString().trim())) {
            return node;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child != null) {
                AccessibilityNodeInfo result = findClickableGrokIcon(child);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findRecentDualAppsCard(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo byDescription = findLargestClickableDescription(root, "dual apps", null);
        if (byDescription != null) {
            return byDescription;
        }
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText("Dual apps")) {
            AccessibilityNodeInfo current = node;
            for (int depth = 0; current != null && depth < 8; depth++) {
                if (current.isClickable()) {
                    return current;
                }
                current = current.getParent();
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findLargestClickableDescription(
            AccessibilityNodeInfo node,
            String expected,
            AccessibilityNodeInfo best
    ) {
        CharSequence description = node.getContentDescription();
        if (node.isClickable() && description != null
                && description.toString().toLowerCase(Locale.ROOT).contains(expected)) {
            if (best == null || nodeArea(node) > nodeArea(best)) {
                best = node;
            }
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child != null) {
                best = findLargestClickableDescription(child, expected, best);
            }
        }
        return best;
    }

    private long nodeArea(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return (long) bounds.width() * bounds.height();
    }

    private AccessibilityNodeInfo findExactText(AccessibilityNodeInfo root, String expected) {
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(expected)) {
            CharSequence text = node.getText();
            if (text != null && expected.equalsIgnoreCase(text.toString().trim())) {
                return node;
            }
        }
        return null;
    }

    private boolean containsText(AccessibilityNodeInfo root, String expected) {
        return !root.findAccessibilityNodeInfosByText(expected).isEmpty();
    }

    private boolean click(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo target = node;
        while (target != null && !target.isClickable()) {
            target = target.getParent();
        }
        if (target == null) {
            return false;
        }
        boolean clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (clicked) {
            lastActionAt = System.currentTimeMillis();
        }
        return clicked;
    }

    private String stage() {
        return preferences(this).getString(KEY_STAGE, STAGE_IDLE);
    }

    private void setStage(String stage) {
        Log.i(TAG, "Etapa: " + stage);
        preferences(this).edit().putString(KEY_STAGE, stage).apply();
    }

    private void complete(String message) {
        preferences(this).edit()
                .putString(KEY_STAGE, STAGE_IDLE)
                .putString(KEY_LAST_RESULT, message)
                .apply();
        handler.removeCallbacks(processWindow);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void fail(String message) {
        preferences(this).edit()
                .putString(KEY_STAGE, STAGE_IDLE)
                .putString(KEY_LAST_RESULT, message)
                .apply();
        handler.removeCallbacks(processWindow);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
