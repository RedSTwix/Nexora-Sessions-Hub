package com.rafael.groksessions;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import static com.rafael.groksessions.AvailabilityMode.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public final class AccountAvailabilityTest {
    private static final long HOUR = 3600000L;
    private final AtomicLong now = new AtomicLong(1700000000000L);
    private Context context;
    private SharedPreferences preferences;
    private AccountRepository repository;
    private AccountFolder folder;
    private Account account;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        preferences = context.getSharedPreferences("account_manager", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        repository = new AccountRepository(context, now::get);
        folder = repository.addFolder("Perfis");
        account = repository.add("Conta", folder.id);
    }

    private Set<String> folderIds() { return Collections.singleton(folder.id); }

    @Test public void openingOneWayDoesNotMarkTheOtherWay() {
        repository.markAccountUnavailable(account, PROFILE_USED);
        assertFalse(repository.isAccountAvailable(account.id, PROFILE_USED));
        assertTrue(repository.isAccountAvailable(account.id, OPEN_WITH));
        repository.resetAvailabilityIndicators(PROFILE_USED);
        repository.markAccountUnavailable(account, OPEN_WITH);
        assertFalse(repository.isAccountAvailable(account.id, OPEN_WITH));
        assertTrue(repository.isAccountAvailable(account.id, PROFILE_USED));
    }

    @Test public void separateDurationsExpireAtTheirOwnBoundaries() {
        repository.saveAvailabilitySettings(folderIds(), 2, OPEN_WITH);
        repository.saveAvailabilitySettings(folderIds(), 6, PROFILE_USED);
        repository.markAccountUnavailable(account, OPEN_WITH);
        repository.markAccountUnavailable(account, PROFILE_USED);
        now.addAndGet(2 * HOUR - 1);
        assertFalse(repository.isAccountAvailable(account.id, OPEN_WITH));
        now.incrementAndGet();
        assertTrue(repository.isAccountAvailable(account.id, OPEN_WITH));
        assertFalse(repository.isAccountAvailable(account.id, PROFILE_USED));
        now.addAndGet(4 * HOUR);
        assertTrue(repository.isAccountAvailable(account.id, PROFILE_USED));
    }

    @Test public void reopeningRenewsOnlyThatModesTimer() {
        repository.saveAvailabilitySettings(folderIds(), 2, OPEN_WITH);
        repository.saveAvailabilitySettings(folderIds(), 2, PROFILE_USED);
        repository.markAccountUnavailable(account, OPEN_WITH);
        repository.markAccountUnavailable(account, PROFILE_USED);
        now.addAndGet(HOUR);
        repository.markAccountUnavailable(account, PROFILE_USED);
        now.addAndGet(HOUR);
        assertTrue(repository.isAccountAvailable(account.id, OPEN_WITH));
        assertFalse(repository.isAccountAvailable(account.id, PROFILE_USED));
    }

    @Test public void disabledFoldersDoNotRecordUseForThatMode() {
        repository.saveAvailabilitySettings(Collections.emptySet(), 4, OPEN_WITH);
        repository.saveAvailabilitySettings(folderIds(), 7, PROFILE_USED);
        repository.markAccountUnavailable(account, OPEN_WITH);
        repository.markAccountUnavailable(account, PROFILE_USED);
        assertTrue(repository.isAccountAvailable(account.id, OPEN_WITH));
        assertFalse(repository.isAccountAvailable(account.id, PROFILE_USED));
        assertEquals(4, repository.getAvailabilityDurationHours(OPEN_WITH));
        assertEquals(7, repository.getAvailabilityDurationHours(PROFILE_USED));
    }

    @Test public void resetOnlyClearsTheChosenModeAndPreservesAccountsAndSettings() {
        repository.markAccountUnavailable(account, OPEN_WITH);
        repository.markAccountUnavailable(account, PROFILE_USED);
        String accountsBefore = preferences.getString("accounts", "[]");
        repository.resetAvailabilityIndicators(PROFILE_USED);
        assertTrue(repository.isAccountAvailable(account.id, PROFILE_USED));
        assertFalse(repository.isAccountAvailable(account.id, OPEN_WITH));
        repository.markAccountUnavailable(account, PROFILE_USED);
        repository.resetAvailabilityIndicators(OPEN_WITH);
        assertTrue(repository.isAccountAvailable(account.id, OPEN_WITH));
        assertFalse(repository.isAccountAvailable(account.id, PROFILE_USED));
        assertEquals(accountsBefore, preferences.getString("accounts", "[]"));
        assertEquals(folderIds(), repository.getAvailabilityEnabledFolderIds(OPEN_WITH));
    }

    @Test public void oldPreferencesRemainUnderOpenWithAndDoNotSeedProfileUsed() throws Exception {
        preferences.edit().putInt("availability_duration_hours", 36)
                .putStringSet("availability_folders", folderIds())
                .putString("account_last_used", new JSONObject().put(account.id, now.get() - HOUR).toString()).commit();
        repository = new AccountRepository(context, now::get);
        assertEquals(36, repository.getAvailabilityDurationHours(OPEN_WITH));
        assertFalse(repository.isAccountAvailable(account.id, OPEN_WITH));
        assertEquals(12, repository.getAvailabilityDurationHours(PROFILE_USED));
        assertTrue(repository.isAccountAvailable(account.id, PROFILE_USED));
        repository.markAccountUnavailable(account, PROFILE_USED);
        repository = new AccountRepository(context, now::get);
        assertFalse(repository.isAccountAvailable(account.id, PROFILE_USED));
    }

    @Test public void oldestDeadlineMigrationAndResetCannotResurrectExpiredIndicators() throws Exception {
        preferences.edit().putString("account_unavailable_until",
                new JSONObject().put(account.id, now.get() + HOUR).toString()).commit();
        repository.resetAvailabilityIndicators(PROFILE_USED);
        assertFalse(repository.isAccountAvailable(account.id, OPEN_WITH));
        assertTrue(repository.isAccountAvailable(account.id, PROFILE_USED));
        repository.resetAvailabilityIndicators(OPEN_WITH);
        assertTrue(repository.isAccountAvailable(account.id, OPEN_WITH));
        assertFalse(preferences.contains("account_unavailable_until"));
    }

    @Test public void folderStatusIsComputedIndependentlyForEachMode() {
        Account second = repository.add("Segunda", folder.id);
        repository.markAccountUnavailable(account, PROFILE_USED);
        assertTrue(repository.hasAvailableAccount(folder.id, PROFILE_USED));
        repository.markAccountUnavailable(second, PROFILE_USED);
        assertFalse(repository.hasAvailableAccount(folder.id, PROFILE_USED));
        assertTrue(repository.hasAvailableAccount(folder.id, OPEN_WITH));
        repository.markAccountUnavailable(account, OPEN_WITH);
        assertTrue(repository.hasAvailableAccount(folder.id, OPEN_WITH));
        assertTrue(repository.hasAvailableAccount(repository.addFolder("Vazia").id, PROFILE_USED));
    }

    @Test public void renamingAndMovingPreserveBothTimersWhileDeletingRemovesBoth() {
        repository.markAccountUnavailable(account, OPEN_WITH);
        repository.markAccountUnavailable(account, PROFILE_USED);
        repository.renameAccount(account.id, "Outro nome");
        repository.moveAccounts(Collections.singleton(account.id), repository.addFolder("Destino").id);
        assertFalse(repository.isAccountAvailable(account.id, PROFILE_USED));
        assertFalse(repository.isAccountAvailable(account.id, OPEN_WITH));
        repository.remove(account.id);
        assertTrue(repository.isAccountAvailable(account.id, OPEN_WITH));
        assertTrue(repository.isAccountAvailable(account.id, PROFILE_USED));
    }

    @Test public void folderChangesAndDurationBoundsKeepBothSettingsIndependent() {
        repository.saveAvailabilitySettings(folderIds(), 0, OPEN_WITH);
        repository.saveAvailabilitySettings(Collections.emptySet(), 900, PROFILE_USED);
        assertEquals(1, repository.getAvailabilityDurationHours(OPEN_WITH));
        assertEquals(720, repository.getAvailabilityDurationHours(PROFILE_USED));
        AccountFolder next = repository.addFolder("Nova");
        assertTrue(repository.isAvailabilityEnabledForFolder(next.id, OPEN_WITH));
        assertTrue(repository.isAvailabilityEnabledForFolder(next.id, PROFILE_USED));
        assertFalse(repository.isAvailabilityEnabledForFolder(folder.id, PROFILE_USED));
        assertTrue(repository.removeFolder(next.id));
        assertFalse(repository.getAvailabilityEnabledFolderIds(OPEN_WITH).contains(next.id));
        assertFalse(repository.getAvailabilityEnabledFolderIds(PROFILE_USED).contains(next.id));
        Set<String> copy = new HashSet<>(repository.getAvailabilityEnabledFolderIds(OPEN_WITH));
        copy.clear();
        assertEquals(folderIds(), repository.getAvailabilityEnabledFolderIds(OPEN_WITH));
    }
}
