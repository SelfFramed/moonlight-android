package com.limelight.selfframed.mic;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;

/**
 * "Mikrofon zum PC" in the stream (docs/selfframed/ARCHITEKTUR.md): Ctrl+Alt+Shift+M switches the phone's
 * microphone to the PC on and off. While it is on, a badge says so; Android's own privacy dot is on the phone's
 * screen, not on the e-ink panel. Off by default and at every stream start. UI thread only.
 */
public final class MicControl {
    /** Setting: the pairing code from the PC receiver (empty = not paired). */
    public static final String PREF_CODE = "sf_mic_code";
    static final int PERMISSION_REQUEST = 0x5F31;

    /** Where short messages go (the stream's notice). */
    public interface Notices {
        void show(String text);
    }

    private final Activity activity;
    private final ViewGroup statusColumn;
    private final Notices notices;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private MicSender sender;
    private TextView badge;
    private boolean released;

    public MicControl(Activity activity, ViewGroup statusColumn, Notices notices) {
        this.activity = activity;
        this.statusColumn = statusColumn;
        this.notices = notices;
    }

    /** Ctrl+Alt+Shift+M. */
    public void onHotkey() {
        if (released) {
            return;
        }
        if (sender != null) {
            stop();
            notices.show(activity.getString(R.string.sf_mic_off));
            return;
        }
        String host = activity.getIntent() != null ? activity.getIntent().getStringExtra(com.limelight.Game.EXTRA_HOST) : null;
        if (host == null) {
            notices.show(activity.getString(R.string.sf_mic_stream_only));
            return;
        }
        String code = MicCode.normalize(PreferenceManager.getDefaultSharedPreferences(activity).getString(PREF_CODE, null));
        if (code == null) {
            notices.show(activity.getString(R.string.sf_mic_no_code));
            return;
        }
        if (MicSender.supportsRuntimePermission() &&
                activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST);
            notices.show(activity.getString(R.string.sf_mic_permission));
            return;
        }
        final MicSender[] self = new MicSender[1];
        self[0] = new MicSender(host, MicCode.key(code), reason -> ui.post(() -> {
            // Only if this sender is still the current one (not switched off and on meanwhile)
            if (sender == self[0]) {
                sender = null;
                renderBadge();
                notices.show(activity.getString(R.string.sf_mic_error, reason));
            }
        }));
        sender = self[0];
        sender.start();
        renderBadge();
    }

    public boolean isOn() {
        return sender != null;
    }

    private void stop() {
        MicSender s = sender;
        sender = null;
        if (s != null) {
            s.stop();
        }
        renderBadge();
    }

    /** Activity.onDestroy(): the microphone never outlives the stream. */
    public void release() {
        stop();
        released = true;
    }

    private void renderBadge() {
        if (sender == null) {
            if (badge != null) {
                badge.setVisibility(View.GONE);
            }
            return;
        }
        if (badge == null) {
            badge = new TextView(activity);
            badge.setText(R.string.sf_mic_on_badge);
            badge.setTextColor(Color.BLACK);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(dp(16), dp(8), dp(16), dp(8));
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.WHITE);
            background.setStroke(dp(3), Color.BLACK);
            badge.setBackground(background);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin = dp(8);
            statusColumn.addView(badge, lp);
        }
        badge.setVisibility(View.VISIBLE);
        statusColumn.bringToFront();
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics()));
    }
}
