package com.limelight.selfframed.mic;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;

/**
 * "Mikrofon zum PC" in the stream (docs/selfframed/ARCHITEKTUR.md): Ctrl+Alt+Shift+M switches the phone's
 * microphone to the PC on and off. The first time for a PC it pairs like Bluetooth: the badge shows a code, the
 * PC asks with the same code, one click there and the microphone is on; afterwards M alone. A missing microphone
 * permission is asked for and the start continues by itself once granted. While on, a badge says so (Android's
 * privacy dot is on the phone's screen, not on the e-ink panel). Off at every stream start. UI thread only.
 */
public final class MicControl {
    static final int PERMISSION_REQUEST = 0x5F31;
    private static final int PERMISSION_POLL_MS = 500;
    private static final int PERMISSION_WAIT_MS = 60_000;

    /** Where short messages go (the stream's notice). */
    public interface Notices {
        void show(String text);
    }

    private final Activity activity;
    private final ViewGroup statusColumn;
    private final Notices notices;
    private final MicPairings pairings;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private MicSender sender;
    private MicPairer pairer;
    private TextView badge;
    private boolean released;
    private long permissionAskedAt;

    private final Runnable waitForPermission = new Runnable() {
        @Override
        public void run() {
            if (released || permissionAskedAt == 0) {
                return;
            }
            if (hasPermission()) {
                permissionAskedAt = 0;
                switchOn();
            } else if (System.currentTimeMillis() - permissionAskedAt < PERMISSION_WAIT_MS) {
                ui.postDelayed(this, PERMISSION_POLL_MS);
            } else {
                permissionAskedAt = 0;
            }
        }
    };

    public MicControl(Activity activity, ViewGroup statusColumn, Notices notices) {
        this.activity = activity;
        this.statusColumn = statusColumn;
        this.notices = notices;
        this.pairings = new MicPairings(activity);
    }

    /** Ctrl+Alt+Shift+M. */
    public void onHotkey() {
        if (released) {
            return;
        }
        if (sender != null || pairer != null) {
            boolean wasPairing = pairer != null;
            stop();
            notices.show(activity.getString(wasPairing ? R.string.sf_mic_pairing_cancelled : R.string.sf_mic_off));
            return;
        }
        if (host() == null) {
            notices.show(activity.getString(R.string.sf_mic_stream_only));
            return;
        }
        if (!hasPermission()) {
            // Continue by itself once Android's question is answered
            permissionAskedAt = System.currentTimeMillis();
            if (MicSender.supportsRuntimePermission()) {
                activity.requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST);
            }
            ui.removeCallbacks(waitForPermission);
            ui.postDelayed(waitForPermission, PERMISSION_POLL_MS);
            return;
        }
        switchOn();
    }

    private String host() {
        return activity.getIntent() != null ? activity.getIntent().getStringExtra(com.limelight.Game.EXTRA_HOST) : null;
    }

    /** Pairings are per PC: Moonlight's UUID of the host, else its address. */
    private String hostId() {
        String uuid = activity.getIntent() != null ? activity.getIntent().getStringExtra(com.limelight.Game.EXTRA_PC_UUID) : null;
        return uuid != null ? uuid : host();
    }

    private boolean hasPermission() {
        return !MicSender.supportsRuntimePermission()
                || activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void switchOn() {
        if (released || sender != null || pairer != null || host() == null) {
            return;
        }
        MicPairings.Entry entry = pairings.get(hostId());
        if (entry != null) {
            startSending(entry.device, entry.key);
        } else {
            startPairing();
        }
    }

    private void startPairing() {
        final String hostId = hostId();
        final MicPairer[] self = new MicPairer[1];
        self[0] = new MicPairer(host(), Build.MODEL, new MicPairer.Listener() {
            @Override
            public void onCode(String code) {
                ui.post(() -> {
                    if (pairer == self[0]) {
                        showBadge(activity.getString(R.string.sf_mic_pairing_badge, code));
                    }
                });
            }

            @Override
            public void onPaired(int device, byte[] audioKey) {
                ui.post(() -> {
                    if (pairer == self[0]) {
                        pairer = null;
                        pairings.put(hostId, device, audioKey);
                        startSending(device, audioKey);
                    }
                });
            }

            @Override
            public void onFailed(String reason, boolean rejected) {
                ui.post(() -> {
                    if (pairer == self[0]) {
                        pairer = null;
                        renderBadge();
                        notices.show(rejected ? activity.getString(R.string.sf_mic_pairing_rejected)
                                : activity.getString(R.string.sf_mic_pairing_failed, reason));
                    }
                });
            }
        });
        pairer = self[0];
        showBadge(activity.getString(R.string.sf_mic_pairing_wait));
        pairer.start();
    }

    private void startSending(int device, byte[] key) {
        final String hostId = hostId();
        final MicSender[] self = new MicSender[1];
        self[0] = new MicSender(host(), device, key, new MicSender.Listener() {
            @Override
            public void onMicFailed(String reason) {
                ui.post(() -> {
                    if (sender == self[0]) {
                        sender = null;
                        renderBadge();
                        notices.show(activity.getString(R.string.sf_mic_error, reason));
                    }
                });
            }

            @Override
            public void onUnpaired() {
                ui.post(() -> {
                    if (sender == self[0]) {
                        // The PC forgot this phone (pairing deleted there): pair again with the next M
                        pairings.remove(hostId);
                        stop();
                        notices.show(activity.getString(R.string.sf_mic_unpaired));
                    }
                });
            }
        });
        sender = self[0];
        sender.start();
        showBadge(activity.getString(R.string.sf_mic_on_badge));
    }

    public boolean isOn() {
        return sender != null;
    }

    private void stop() {
        permissionAskedAt = 0;
        ui.removeCallbacks(waitForPermission);
        MicPairer p = pairer;
        pairer = null;
        if (p != null) {
            p.cancel();
        }
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
        if (sender == null && pairer == null && badge != null) {
            badge.setVisibility(View.GONE);
        }
    }

    private void showBadge(String text) {
        if (badge == null) {
            badge = new TextView(activity);
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
        if (!text.contentEquals(badge.getText())) {
            badge.setText(text);
        }
        badge.setVisibility(View.VISIBLE);
        statusColumn.bringToFront();
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics()));
    }

    /** Settings: forget all pairings (the PCs keep theirs until deleted there). */
    public static int pairingCount(Activity activity) {
        return new MicPairings(activity).count();
    }

    public static void forgetAll(Activity activity) {
        new MicPairings(activity).clear();
    }
}
