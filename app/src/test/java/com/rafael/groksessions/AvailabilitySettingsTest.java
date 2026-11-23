package com.rafael.groksessions;

import android.app.Dialog;
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
import org.robolectric.util.ReflectionHelpers;
import java.util.Collections;
import static com.rafael.groksessions.AvailabilityMode.*;
import static org.junit.Assert.*;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public final class AvailabilitySettingsTest {
    private MainActivity activity;
    private AccountRepository repository;
    private Account account;
    private AppUpdater updater;

    @Before public void prepare() {
        activity = Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(android.R.style.Theme_Material_NoActionBar);
        activity.getSharedPreferences("account_manager", 0).edit().clear().commit();
        repository = new AccountRepository(activity);
        AccountFolder folder = repository.addFolder("Perfis");
        account = repository.add("Conta de teste", folder.id);
        updater = new AppUpdater(activity, () -> {});
        ReflectionHelpers.setField(activity, "repository", repository);
        ReflectionHelpers.setField(activity, "updater", updater);
    }

    @After public void finish() {
        Dialog dialog = ShadowDialog.getLatestDialog();
        if (dialog != null) dialog.dismiss();
        updater.close();
    }

    @Test public void separateSettingsValidateSaveAndResetOnlyTheSelectedMode() {
        repository.saveAvailabilitySettings(Collections.singleton(account.folderId), 2, OPEN_WITH);
        repository.markAccountUnavailable(account, OPEN_WITH);
        repository.markAccountUnavailable(account, PROFILE_USED);
        ReflectionHelpers.callInstanceMethod(activity, "showAvailabilitySettings");
        Dialog root = ShadowDialog.getLatestDialog();
        assertNotNull(findText(root.getWindow().getDecorView(), "Aberto com"));
        click(root, "Configurar Perfil usado");
        Dialog profile = ShadowDialog.getLatestDialog();
        click(profile, "↻  Liberar todos agora");
        assertTrue(repository.isAccountAvailable(account.id, PROFILE_USED));
        assertFalse(repository.isAccountAvailable(account.id, OPEN_WITH));
        EditText hours = findInput(profile.getWindow().getDecorView());
        hours.setText("0");
        click(profile, "SALVAR");
        assertNotNull(hours.getError());
        assertTrue(profile.isShowing());
        hours.setText("9");
        click(profile, "SALVAR");
        assertEquals(9, repository.getAvailabilityDurationHours(PROFILE_USED));
        assertEquals(2, repository.getAvailabilityDurationHours(OPEN_WITH));
        assertFalse(profile.isShowing());
    }

    @Test public void normalCardsAndExternalPickerDisplayTheirOwnState() {
        repository.markAccountUnavailable(account, PROFILE_USED);
        View normal = ReflectionHelpers.callInstanceMethod(activity, "accountCard", from(Account.class, account));
        View picker = ReflectionHelpers.callInstanceMethod(activity, "createAccountPickerRow", from(Account.class, account),
                from(View.OnClickListener.class, null));
        assertNotNull(findDescription(normal, "Perfil usado · Em espera"));
        assertNotNull(findDescription(picker, "Aberto com · Disponível"));
        repository.resetAvailabilityIndicators(PROFILE_USED);
        repository.markAccountUnavailable(account, OPEN_WITH);
        normal = ReflectionHelpers.callInstanceMethod(activity, "accountCard", from(Account.class, account));
        picker = ReflectionHelpers.callInstanceMethod(activity, "createAccountPickerRow", from(Account.class, account),
                from(View.OnClickListener.class, null));
        assertNotNull(findDescription(normal, "Perfil usado · Disponível"));
        assertNotNull(findDescription(picker, "Aberto com · Em espera"));
    }

    @Test public void settingsExposeBothBackupActions() {
        ReflectionHelpers.callInstanceMethod(activity, "showAvailabilitySettings");
        Dialog settings = ShadowDialog.getLatestDialog();
        assertNotNull(findText(settings.getWindow().getDecorView(), "Backup e restauração"));
        assertNotNull(findText(settings.getWindow().getDecorView(), "Criar backup ZIP"));
        assertNotNull(findText(settings.getWindow().getDecorView(), "Restaurar backup ZIP"));
    }

    @Test public void manualYellowAppearsInNormalCardsWithoutAffectingExternalPicker() {
        repository.markAccountUnavailable(account, PROFILE_USED);
        repository.cycleProfileIndicator(account);
        View normal = ReflectionHelpers.callInstanceMethod(activity, "accountCard", from(Account.class, account));
        View picker = ReflectionHelpers.callInstanceMethod(activity, "createAccountPickerRow", from(Account.class, account),
                from(View.OnClickListener.class, null));
        assertNotNull(findDescription(normal, "Perfil usado · Amarelo"));
        assertNotNull(findDescription(picker, "Aberto com · Disponível"));
    }

    private static void click(Dialog dialog, String label) {
        TextView button = findText(dialog.getWindow().getDecorView(), label);
        assertNotNull("Botão ausente: " + label, button);
        button.performClick();
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().equals(text)) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView found = findText(((ViewGroup) view).getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }

    private static View findDescription(View view, String prefix) {
        if (view.getContentDescription() != null && view.getContentDescription().toString().startsWith(prefix)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = findDescription(((ViewGroup) view).getChildAt(i), prefix);
            if (found != null) return found;
        }
        return null;
    }

    private static EditText findInput(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            EditText found = findInput(((ViewGroup) view).getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
}
