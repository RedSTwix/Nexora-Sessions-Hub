package com.rafael.groksessions;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;

/** Manual control of Perfil usado, sharing the list's persisted state and timer. */
@SuppressLint("ViewConstructor") // Created in code with an existing profile; never inflated from XML.
final class ProfileIndicatorButton extends TextView {
    private final AccountRepository repository;
    private final String profileId;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = this::refresh;
    private boolean refreshing;

    ProfileIndicatorButton(Context context, AccountRepository repository, String profileId) {
        super(context);
        this.repository = repository;
        this.profileId = profileId;
        setText("●");
        setTextSize(21);
        setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(13, 30, 42));
        background.setCornerRadius(12 * getResources().getDisplayMetrics().density);
        setBackground(new RippleDrawable(ColorStateList.valueOf(0x3342EEE2), background, null));
        setOnClickListener(view -> {
            Account account = currentAccount();
            if (account != null && !repository.cycleProfileIndicator(account))
                Toast.makeText(context, "Não foi possível alterar o indicador.", Toast.LENGTH_SHORT).show();
            refresh();
        });
        refresh();
    }

    void startRefreshing() {
        refreshing = true;
        refresh();
    }

    void stopRefreshing() {
        refreshing = false;
        handler.removeCallbacks(refreshTask);
    }

    private Account currentAccount() {
        for (Account account : repository.getAll()) if (account.id.equals(profileId)) return account;
        return null;
    }

    private void refresh() {
        handler.removeCallbacks(refreshTask);
        Account account = currentAccount();
        boolean enabled = account != null && repository.isAvailabilityEnabledForFolder(
                account.folderId, AvailabilityMode.PROFILE_USED);
        setVisibility(enabled ? VISIBLE : GONE);
        if (enabled) {
            AvailabilityStatus status = repository.getAvailabilityStatus(profileId, AvailabilityMode.PROFILE_USED);
            setTextColor(status.color);
            setContentDescription("Perfil usado · " + status.description(repository.getAvailabilityDurationHours(
                    AvailabilityMode.PROFILE_USED)) + " · Toque para marcar "
                    + repository.nextProfileIndicatorStatus(profileId).label);
        }
        if (refreshing) {
            long remaining = repository.availabilityRemainingMillis(profileId, AvailabilityMode.PROFILE_USED);
            handler.postDelayed(refreshTask, remaining > 0 ? Math.min(60000L, remaining) : 60000L);
        }
    }
}
