package com.limelight.selfframed.battery;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.limelight.LimeLog;
import com.limelight.R;

/**
 * Battery warnings for the client inside the stream (docs/selfframed/ARCHITEKTUR.md): the phone's
 * status bar and Android's own warnings are not visible on the e-ink lapdock. A badge at the top
 * centre in the e-ink style of the other notices (black on white, thick border, no transparency,
 * no animation): a short notice at the first threshold, a notice that stays from the second one
 * until the battery charges, and the battery state on demand (Ctrl+Alt+Shift+B). The decisions
 * are made by {@link BatteryWatch}. UI thread only.
 */
public final class BatteryWarner {
    public static final String PREF_ENABLED = "sf_battery_warn";
    public static final String PREF_LOW = "sf_battery_low";
    public static final String PREF_CRITICAL = "sf_battery_critical";
    private static final int DEFAULT_LOW = 15;
    private static final int DEFAULT_CRITICAL = 7;

    private static final int LOW_NOTICE_MS = 8000;
    private static final int SHORT_NOTICE_MS = 4000;
    /** At stream start the stream's own notice goes first. */
    private static final int FIRST_CHECK_DELAY_MS = 2500;

    private final Activity activity;
    private final FrameLayout parent;
    private final BatteryWatch watch;
    private final boolean warningsEnabled;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final long createdAt = SystemClock.uptimeMillis();

    // Last known state, for display (independent of whether warnings are on)
    private int percent = -1;
    private boolean charging;
    private boolean plugged;
    private boolean full;

    private TextView badge;
    private boolean badgeUrgent;
    private String transientText;
    private Intent delayedIntent;
    private boolean registered;
    private boolean released;

    private final Runnable endTransient = new Runnable() {
        @Override
        public void run() {
            transientText = null;
            render();
        }
    };

    private final Runnable processDelayed = new Runnable() {
        @Override
        public void run() {
            Intent i = delayedIntent;
            delayedIntent = null;
            if (i != null) {
                onBattery(i);
            }
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (SystemClock.uptimeMillis() - createdAt < FIRST_CHECK_DELAY_MS) {
                // Keep only the newest state until the stream's start notice is gone
                if (delayedIntent == null) {
                    ui.postAtTime(processDelayed, createdAt + FIRST_CHECK_DELAY_MS);
                }
                delayedIntent = intent;
                return;
            }
            onBattery(intent);
        }
    };

    private BatteryWarner(Activity activity, FrameLayout parent, boolean warningsEnabled, int lowPercent,
                          int criticalPercent) {
        this.activity = activity;
        this.parent = parent;
        this.warningsEnabled = warningsEnabled;
        this.watch = new BatteryWatch(lowPercent, criticalPercent);
    }

    /** Created with the stream's controller; watches the battery if the warnings are switched on. */
    public static BatteryWarner create(Activity activity, FrameLayout parent) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        BatteryWarner w = new BatteryWarner(activity, parent, prefs.getBoolean(PREF_ENABLED, true),
                parsePercent(prefs.getString(PREF_LOW, null), DEFAULT_LOW),
                parsePercent(prefs.getString(PREF_CRITICAL, null), DEFAULT_CRITICAL));
        if (w.warningsEnabled) {
            w.registerForUpdates();
        }
        return w;
    }

    private static int parsePercent(String value, int fallback) {
        try {
            return value != null ? Integer.parseInt(value) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void registerForUpdates() {
        try {
            registerReceiver(receiver);
            registered = true;
            LimeLog.info("SelfFramed battery: warnings at " + watch.lowPercent() + " % and " +
                    watch.criticalPercent() + " %");
        } catch (RuntimeException e) {
            LimeLog.warning("SelfFramed battery: no battery updates: " + e);
        }
    }

    /** With a null receiver this only returns the current (sticky) battery state. */
    private Intent registerReceiver(BroadcastReceiver r) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            return activity.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED);
        }
        return activity.registerReceiver(r, filter);
    }

    /** Activity.onDestroy(). */
    public void release() {
        released = true;
        ui.removeCallbacksAndMessages(null);
        if (registered) {
            registered = false;
            try {
                activity.unregisterReceiver(receiver);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * Ctrl+Alt+Shift+B: hides the critical notice until the next percent drop, otherwise shows
     * the battery state for a few seconds (also when the automatic warnings are off).
     */
    public void onHotkey() {
        if (released) {
            return;
        }
        if (watch.snooze()) {
            transientText = null;
            ui.removeCallbacks(endTransient);
            render();
            return;
        }
        if (!registered || percent < 0) {
            try {
                Intent sticky = registerReceiver(null);
                if (sticky != null) {
                    readState(sticky);
                }
            } catch (RuntimeException e) {
                LimeLog.warning("SelfFramed battery: no battery information: " + e);
            }
        }
        String text = percent < 0 ? activity.getString(R.string.sf_battery_unknown)
                : activity.getString(R.string.sf_battery_status, percent) + "\n" + describePower();
        showTransient(text, SHORT_NOTICE_MS);
    }

    private void onBattery(Intent intent) {
        if (released) {
            return;
        }
        int beforePercent = percent;
        boolean beforeCharging = charging;
        boolean beforePlugged = plugged;
        if (!readState(intent)) {
            return;
        }
        if (watch.isKnown() && beforePercent == percent && beforeCharging == charging && beforePlugged == plugged) {
            // Voltage or temperature update only
            return;
        }
        switch (watch.update(percent, charging)) {
            case LOW:
                showTransient(withPowerHint(activity.getString(R.string.sf_battery_low, percent)), LOW_NOTICE_MS);
                break;
            case CHARGING:
                showTransient(activity.getString(R.string.sf_battery_charging, percent), SHORT_NOTICE_MS);
                break;
            default:
                // The critical notice may have to appear, count down or go away
                render();
                break;
        }
    }

    /** @return false if the intent holds no battery level */
    private boolean readState(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) {
            return false;
        }
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        full = status == BatteryManager.BATTERY_STATUS_FULL;
        // Charging means the battery charges; a weak lapdock can be plugged in and still drain it
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || full;
        plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        percent = Math.round(level * 100f / scale);
        return true;
    }

    private String describePower() {
        if (full) {
            return activity.getString(R.string.sf_battery_state_full);
        }
        if (charging) {
            return activity.getString(R.string.sf_battery_state_charging);
        }
        return activity.getString(plugged ? R.string.sf_battery_state_plugged_not_charging
                : R.string.sf_battery_state_unplugged);
    }

    /** Adds the power state when it is the surprising one: plugged in but not charging. */
    private String withPowerHint(String text) {
        if (plugged && !charging) {
            return text + "\n" + activity.getString(R.string.sf_battery_state_plugged_not_charging);
        }
        return text;
    }

    private void showTransient(String text, int durationMs) {
        transientText = text;
        ui.removeCallbacks(endTransient);
        ui.postDelayed(endTransient, durationMs);
        render();
    }

    /** Shows the short notice, else the critical notice, else nothing. */
    private void render() {
        if (released) {
            return;
        }
        String text;
        boolean urgent;
        if (transientText != null) {
            text = transientText;
            urgent = false;
        } else if (warningsEnabled && watch.criticalVisible()) {
            text = withPowerHint(activity.getString(R.string.sf_battery_critical, percent));
            urgent = true;
        } else {
            if (badge != null && badge.getVisibility() != View.GONE) {
                badge.setVisibility(View.GONE);
            }
            return;
        }
        ensureBadge();
        // E-ink: touch the view only when something changes, every change costs a refresh
        if (!text.contentEquals(badge.getText())) {
            badge.setText(text);
        }
        if (urgent != badgeUrgent) {
            badgeUrgent = urgent;
            styleBadge();
        }
        if (badge.getVisibility() != View.VISIBLE) {
            badge.setVisibility(View.VISIBLE);
            badge.bringToFront();
        }
    }

    private void ensureBadge() {
        if (badge != null) {
            return;
        }
        badge = new TextView(activity);
        badge.setTextColor(Color.BLACK);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(20), dp(12), dp(20), dp(12));
        badge.setFocusable(false);
        badge.setClickable(false);
        styleBadge();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(24);
        parent.addView(badge, lp);
    }

    /** No colour: urgency is a thicker border. */
    private void styleBadge() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setStroke(dp(badgeUrgent ? 7 : 3), Color.BLACK);
        badge.setBackground(background);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics()));
    }
}
