package com.rafael.groksessions;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import static com.rafael.groksessions.AvailabilityMode.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public final class ProfileIndicatorButtonTest {
    private final AtomicLong now = new AtomicLong(1700000000000L);
    private AccountRepository repository;
    private Account account;
    private ProfileIndicatorButton button;

    @Before public void prepare() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("account_manager", 0).edit().clear().commit();
        repository = new AccountRepository(context, now::get);
        account = repository.add("Conta", repository.addFolder("Perfis").id);
        repository.markAccountUnavailable(account, PROFILE_USED);
        button = new ProfileIndicatorButton(context, repository, account.id);
    }

    @Test public void tappingCyclesTheSavedStateAndDescribesTheNextAction() {
        assertEquals(AvailabilityStatus.RED.color, button.getCurrentTextColor());
        assertTrue(button.getContentDescription().toString().endsWith("Toque para marcar amarelo"));
        button.performClick();
        assertEquals(AvailabilityStatus.YELLOW.color, button.getCurrentTextColor());
        assertTrue(button.getContentDescription().toString().contains("Amarelo"));
        button.performClick();
        assertEquals(AvailabilityStatus.RED.color, button.getCurrentTextColor());
        assertTrue(button.getContentDescription().toString().endsWith("Toque para marcar verde"));
        button.performClick();
        assertEquals(AvailabilityStatus.GREEN.color, button.getCurrentTextColor());
        assertTrue(repository.isAccountAvailable(account.id, PROFILE_USED));
        assertTrue(repository.isAccountAvailable(account.id, OPEN_WITH));
    }

    @Test public void buttonExpiresWhileVisibleAndRefreshesAfterReturningFromBackground() {
        button.performClick();
        now.addAndGet(12 * 3600000L - 10);
        button.startRefreshing();
        assertEquals(AvailabilityStatus.YELLOW.color, button.getCurrentTextColor());
        now.addAndGet(10);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));
        assertEquals(AvailabilityStatus.GREEN.color, button.getCurrentTextColor());
        button.performClick();
        button.stopRefreshing();
        now.addAndGet(12 * 3600000L);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1));
        assertEquals(AvailabilityStatus.YELLOW.color, button.getCurrentTextColor());
        button.startRefreshing();
        assertEquals(AvailabilityStatus.GREEN.color, button.getCurrentTextColor());
        button.stopRefreshing();
    }

    @Test public void disabledFolderAndDeletedProfileHideButtonWithoutChangingOtherMode() {
        repository.markAccountUnavailable(account, OPEN_WITH);
        repository.saveAvailabilitySettings(Collections.emptySet(), 1, PROFILE_USED);
        button.startRefreshing();
        assertEquals(View.GONE, button.getVisibility());
        assertEquals(AvailabilityStatus.RED, repository.getAvailabilityStatus(account.id, OPEN_WITH));
        repository.saveAvailabilitySettings(Collections.singleton(account.folderId), 1, PROFILE_USED);
        button.startRefreshing();
        assertEquals(View.VISIBLE, button.getVisibility());
        repository.remove(account.id);
        button.startRefreshing();
        assertEquals(View.GONE, button.getVisibility());
        button.stopRefreshing();
    }
}
