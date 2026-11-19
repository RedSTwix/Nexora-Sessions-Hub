package com.rafael.groksessions;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public final class BackupPickerTest {
    @Test public void exportUsesTheSystemZipPickerWithoutAStoragePermissionOrPasswordForm() {
        Intent intent = new Intent().putExtra("source_info", "{\"version_code\":43}");
        ActivityController<BackupActivity> controller = Robolectric.buildActivity(BackupActivity.class, intent).create();
        BackupActivity activity = controller.get();
        try {
            find(activity.getWindow().getDecorView(), "Escolher onde salvar").performClick();
            Intent picker = shadowOf(activity).getNextStartedActivityForResult().intent;
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, picker.getAction());
            assertEquals("application/zip", picker.getType());
            assertTrue(picker.getStringExtra(Intent.EXTRA_TITLE).endsWith(".zip"));
        } finally { controller.destroy(); }
    }

    @Test public void restoreUsesTheSystemOpenDocumentPicker() {
        Intent intent = new Intent().putExtra("restore", true).putExtra("source_info", "{\"version_code\":43}");
        ActivityController<BackupActivity> controller = Robolectric.buildActivity(BackupActivity.class, intent).create();
        BackupActivity activity = controller.get();
        try {
            find(activity.getWindow().getDecorView(), "Selecionar backup ZIP").performClick();
            Intent picker = shadowOf(activity).getNextStartedActivityForResult().intent;
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.getAction());
            assertEquals("application/zip", picker.getType());
        } finally { controller.destroy(); }
    }

    private static TextView find(View view, String label) {
        if (view instanceof TextView && ((TextView) view).getText().toString().equals(label)) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView found = find(((ViewGroup) view).getChildAt(i), label);
            if (found != null) return found;
        }
        return null;
    }
}
