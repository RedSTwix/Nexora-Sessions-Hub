package com.rafael.groksessions;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public final class SpeedDialViewTest {
    private Activity activity;
    private SiteShortcutRepository repository;
    private SpeedDialView home;
    private final List<String> opened = new ArrayList<>();

    @Before public void prepare() {
        activity = Robolectric.buildActivity(Activity.class).get();
        activity.setTheme(android.R.style.Theme_Material_NoActionBar);
        activity.getSharedPreferences("account_manager", 0).edit().clear().commit();
        repository = new SiteShortcutRepository(activity, "first");
        home = new SpeedDialView(activity, repository, "Perfil um", opened::add);
    }

    @After public void finish() { home.closeDialogs(); }

    @Test public void emptyHomeValidatesNewShortcutsAndOpensThemInTheOwningProfile() {
        assertTrue(opened.isEmpty());
        findText(home, "+   Adicionar site").performClick();
        shadowOf(Looper.getMainLooper()).idle();
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        List<EditText> fields = inputs(dialog.getWindow().getDecorView());
        fields.get(0).setText("Meu site");
        fields.get(1).setText("javascript:alert(1)");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertNotNull(fields.get(1).getError());
        assertTrue(dialog.isShowing());
        fields.get(1).setText("example.test/path");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(dialog.isShowing());
        assertEquals(1, repository.getAll().size());
        assertTrue(new SiteShortcutRepository(activity, "second").getAll().isEmpty());
        findDescription(home, "Abrir Meu site, https://example.test/path").performClick();
        assertEquals(java.util.Collections.singletonList("https://example.test/path"), opened);
        SpeedDialView reopened = new SpeedDialView(activity, new SiteShortcutRepository(activity, "first"), "Perfil um", opened::add);
        assertNotNull(findText(reopened, "Meu site"));
        reopened.closeDialogs();
    }

    @Test public void optionsActuallyReorderEditAndDeleteShortcuts() {
        repository.add("Primeiro", "one.example.test", null);
        repository.add("Segundo", "two.example.test", null);
        home.refresh();
        findDescription(home, "Opções do atalho Segundo").performClick();
        selectMenuItem(1); // Move before the first item.
        assertEquals("Segundo", repository.getAll().get(0).name);
        findDescription(home, "Opções do atalho Segundo").performClick();
        selectMenuItem(0); // Edit.
        AlertDialog edit = (AlertDialog) ShadowDialog.getLatestDialog();
        List<EditText> fields = inputs(edit.getWindow().getDecorView());
        fields.get(0).setText("Renomeado");
        edit.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertNotNull(findText(home, "Renomeado"));
        findDescription(home, "Opções do atalho Renomeado").performClick();
        selectMenuItem(2); // Delete, after Edit and Move after.
        AlertDialog confirm = (AlertDialog) ShadowDialog.getLatestDialog();
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, repository.getAll().size());
        assertEquals("Primeiro", repository.getAll().get(0).name);
    }

    private static void selectMenuItem(int index) {
        AlertDialog menu = (AlertDialog) ShadowDialog.getLatestDialog();
        menu.getListView().performItemClick(menu.getListView().getChildAt(index), index, index);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static TextView findText(View view, String value) {
        if (view instanceof TextView && ((TextView) view).getText().toString().equals(value)) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView found = findText(((ViewGroup) view).getChildAt(i), value);
            if (found != null) return found;
        }
        return null;
    }

    private static View findDescription(View view, String value) {
        if (value.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = findDescription(((ViewGroup) view).getChildAt(i), value);
            if (found != null) return found;
        }
        return null;
    }

    private static List<EditText> inputs(View view) {
        List<EditText> result = new ArrayList<>();
        if (view instanceof EditText) result.add((EditText) view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) result.addAll(inputs(((ViewGroup) view).getChildAt(i)));
        return result;
    }
}
