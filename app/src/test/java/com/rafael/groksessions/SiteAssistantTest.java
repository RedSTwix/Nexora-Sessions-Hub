package com.rafael.groksessions;

import android.content.Context;
import android.content.SharedPreferences;
import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
import android.widget.EditText;
import android.widget.LinearLayout;
import org.json.JSONObject;
import org.robolectric.Robolectric;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.RuntimeEnvironment;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public final class SiteAssistantTest {
    @Test public void registryOnlyEnablesTheExactHttpsGrokGenerator() {
        assertTrue(SiteAssistant.supports("https://vidogrid.ai/app/video-gen"));
        assertTrue(SiteAssistant.supports("https://vidogrid.ai/app/video-gen/grok/"));
        assertTrue(SiteAssistant.supports("https://vidogrid.ai:443/app/video-gen/grok?source=shortcut#output"));
        for (String url : new String[]{null, "", "http://vidogrid.ai/app/video-gen/grok",
                "https://vidogrid.ai.evil.test/app/video-gen/grok", "https://other.vidogrid.ai/app/video-gen/grok",
                "https://vidogrid.ai:8443/app/video-gen/grok", "https://vidogrid.ai/history",
                "https://vidogrid.ai/app/video-gen/veo", "https://vidogrid.ai/app/video-gen/grok/other"})
            assertFalse(String.valueOf(url), SiteAssistant.supports(url));
    }

    @Test public void limitsPersistPerProfileAndAreRemovedWithTheirAccount() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = context.getSharedPreferences("account_manager", 0);
        preferences.edit().clear().commit();
        AccountRepository repository = new AccountRepository(context);
        AccountFolder folder = repository.addFolder("Pasta");
        Account first = repository.add("Primeira", folder.id), second = repository.add("Segunda", folder.id);
        SiteAssistantSettings settings = new SiteAssistantSettings(context);
        assertEquals(5, settings.acceptedLimit(first.id));
        assertTrue(settings.saveAcceptedLimit(first.id, 0));
        assertTrue(settings.saveAcceptedLimit(second.id, 7));
        assertEquals(0, new SiteAssistantSettings(context).acceptedLimit(first.id));
        assertEquals(7, new SiteAssistantSettings(context).acceptedLimit(second.id));
        assertFalse(settings.saveAcceptedLimit(second.id, -1));
        repository.remove(first.id);
        assertFalse(preferences.contains(SiteAssistantSettings.key(first.id)));
        assertEquals(7, settings.acceptedLimit(second.id));
    }

    @Test public void backupRestoresZeroAndCustomLimitsAndOldBackupsDefaultToFive() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = context.getSharedPreferences("account_manager", 0);
        preferences.edit().clear().commit();
        AccountRepository repository = new AccountRepository(context);
        AccountFolder folder = repository.addFolder("Pasta");
        Account first = repository.add("Primeira", folder.id), second = repository.add("Segunda", folder.id);
        SiteAssistantSettings settings = new SiteAssistantSettings(context);
        settings.saveAcceptedLimit(first.id, 0); settings.saveAcceptedLimit(second.id, 9);
        JSONObject backup = new JSONObject(BackupSettings.export(preferences).toString());
        preferences.edit().clear().commit(); BackupSettings.restore(preferences, backup);
        assertEquals(0, settings.acceptedLimit(first.id)); assertEquals(9, settings.acceptedLimit(second.id));
        backup.remove(SiteAssistantSettings.key(first.id)); backup.remove(SiteAssistantSettings.key(second.id));
        BackupSettings.restore(preferences, backup);
        assertEquals(5, settings.acceptedLimit(first.id));
        for (Object invalid : new Object[]{-1, "5", 1.5, 2147483648L}) {
            JSONObject bad = new JSONObject(backup.toString()).put(SiteAssistantSettings.key(first.id), invalid);
            try { BackupSettings.validate(bad); fail("Invalid limit accepted"); } catch (java.io.IOException expected) {}
        }
        try { BackupSettings.validate(new JSONObject(backup.toString()).put(SiteAssistantSettings.key("unknown"), 5));
            fail("Unknown profile accepted"); } catch (java.io.IOException expected) {}
    }

    @Test public void settingsBridgeOnlyAcceptsTheExactCommandFromTheMainSiteOrigin() {
        assertTrue(SiteAssistant.allowsSettingsRequest(Uri.parse("https://vidogrid.ai"), true, "settings"));
        assertFalse(SiteAssistant.allowsSettingsRequest(Uri.parse("https://vidogrid.ai"), false, "settings"));
        assertFalse(SiteAssistant.allowsSettingsRequest(Uri.parse("https://other.test"), true, "settings"));
        assertFalse(SiteAssistant.allowsSettingsRequest(Uri.parse("http://vidogrid.ai"), true, "settings"));
        assertFalse(SiteAssistant.allowsSettingsRequest(Uri.parse("https://vidogrid.ai:8443"), true, "settings"));
        assertFalse(SiteAssistant.allowsSettingsRequest(Uri.parse("https://vidogrid.ai"), true, "save:other:0"));
    }

    @Test public void nativeSettingsDialogValidatesAndSavesZero() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(android.R.style.Theme_Material_NoActionBar);
        android.webkit.WebView view = new android.webkit.WebView(activity);
        SiteAssistant assistant = new SiteAssistant(activity, view, "dialog-profile");
        ReflectionHelpers.callInstanceMethod(assistant, "showSettings");
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        LinearLayout layout = (LinearLayout) ((android.widget.FrameLayout) dialog.findViewById(android.R.id.custom)).getChildAt(0);
        EditText input = (EditText) layout.getChildAt(1);
        assertEquals("5", input.getText().toString());
        input.setText(""); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(dialog.isShowing()); assertNotNull(input.getError());
        input.setText("0"); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(dialog.isShowing());
        assertEquals(0, new SiteAssistantSettings(activity).acceptedLimit("dialog-profile"));
        assistant.close(); view.destroy(); activity.finish();
    }
}
