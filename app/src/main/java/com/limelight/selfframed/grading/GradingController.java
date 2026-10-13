package com.limelight.selfframed.grading;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.selfframed.ui.GradingPanelView;

import java.util.HashSet;
import java.util.Set;

/**
 * Glue between the stream activity (Game) and the picture feature: owns the GPU stage,
 * the preset store, the hotkeys, the on-screen notice and the in-stream panel.
 *
 * Game only talks to this class, through the hooks marked [SelfFramed]. Everything here runs
 * on the UI thread unless stated otherwise.
 *
 * Mouse: opening the panel with Ctrl+Alt+Shift+P frees the mouse for the panel, but only once
 * all modifiers are released (plus 250 ms), exactly like upstream's Ctrl+Alt+Shift+Z. Freeing it
 * while the modifiers are still down would drop their key-ups and leave them stuck on the host.
 * Closing the panel captures the mouse again.
 */
public final class GradingController implements GradingEditorHost {
    /** Master switch (settings). Off = exact upstream behaviour, this class is not created. */
    public static final String PREF_ENABLED = "sf_grading_enabled";
    /** Show the short notice when the preset changes. */
    public static final String PREF_OSD = "sf_grading_osd";
    /** Always show the notice at stream start (otherwise only for the first few streams). */
    public static final String PREF_START_NOTICE = "sf_grading_start_notice";

    private static final String UI_PREFS = "sf_grading_ui";
    private static final String KEY_START_NOTICES = "start_notices_shown";
    private static final int START_NOTICE_LIMIT = 3;
    private static final int MOUSE_RELEASE_DELAY_MS = 250;

    /** What the controller needs from the stream activity. */
    public interface StreamHost {
        boolean isInputGrabbed();

        /** Captures (true) or releases (false) mouse and keyboard, like Ctrl+Alt+Shift+Z. */
        void setInputGrabbed(boolean grabbed);
    }

    private static final StreamHost NO_STREAM = new StreamHost() {
        @Override
        public boolean isInputGrabbed() {
            return false;
        }

        @Override
        public void setInputGrabbed(boolean grabbed) {
        }
    };

    private static final int OSD_DURATION_MS = 1800;
    private static final int SAVE_DELAY_MS = 400;

    private final Activity activity;
    private final FrameLayout overlayParent;
    private final View streamView;
    private final boolean hdrStream;
    private final boolean osdEnabled;
    private final StreamHost streamHost;
    private final PresetStore store;
    /** Battery warnings on the stream (selfframed/battery); part of the stream UI like the notices. */
    private final com.limelight.selfframed.battery.BatteryWarner battery;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private volatile GradingRenderer renderer;
    private String unavailableReason;
    private GradingPanel panel;
    private boolean panelOpen;
    /** The panel was opened by a hotkey; free the mouse once the combo's modifiers are up. */
    private boolean releaseMouseWhenComboEnds;
    /** We freed the mouse for the panel and must capture it again on close. */
    private boolean mouseReleasedByPanel;
    /** Key codes whose key-down the panel consumed; only their key-ups are consumed too. */
    private final Set<Integer> panelKeysDown = new HashSet<>();
    private TextView osdView;
    private int lastPresetSlot;
    private boolean lastKeyWasRepeat;
    private boolean destroyed;

    private final Runnable hideOsd = new Runnable() {
        @Override
        public void run() {
            if (osdView != null) {
                osdView.setVisibility(View.GONE);
            }
        }
    };

    private final Runnable releaseMouse = new Runnable() {
        @Override
        public void run() {
            if (panelOpen && streamHost.isInputGrabbed()) {
                mouseReleasedByPanel = true;
                streamHost.setInputGrabbed(false);
            }
        }
    };

    private final Runnable saveNow = new Runnable() {
        @Override
        public void run() {
            store.save();
        }
    };

    /**
     * @return the controller, or null if the picture stage is switched off in the settings
     */
    public static GradingController create(Activity activity, FrameLayout overlayParent, View streamView,
                                           boolean hdrStream, StreamHost streamHost) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        if (!prefs.getBoolean(PREF_ENABLED, true)) {
            LimeLog.info("SelfFramed grading: disabled in settings");
            return null;
        }
        return new GradingController(activity, overlayParent, streamView, hdrStream, streamHost,
                prefs.getBoolean(PREF_OSD, true));
    }

    /** For the test pattern screen: always on, regardless of the stream setting. */
    public static GradingController createForPreview(Activity activity, FrameLayout overlayParent, View view) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        return new GradingController(activity, overlayParent, view, false, NO_STREAM, prefs.getBoolean(PREF_OSD, true));
    }

    private GradingController(Activity activity, FrameLayout overlayParent, View streamView, boolean hdrStream,
                              StreamHost streamHost, boolean osdEnabled) {
        this.activity = activity;
        this.overlayParent = overlayParent;
        this.streamView = streamView;
        this.hdrStream = hdrStream;
        this.streamHost = streamHost != null ? streamHost : NO_STREAM;
        this.osdEnabled = osdEnabled;
        this.store = new PresetStore(activity);
        this.battery = com.limelight.selfframed.battery.BatteryWarner.create(activity, overlayParent);
        int active = store.bundle().getActiveSlot();
        this.lastPresetSlot = PresetBundle.isPresetSlot(active) ? active : 1;

        // SurfaceView defaults to RGB_565. The decoder overrides that with its own buffers, but our
        // GL output should be explicitly 8 bit per channel (opaque). Must happen before the
        // surface exists, which is why it is done here (Game creates us in onCreate).
        if (!hdrStream && streamView instanceof SurfaceView) {
            ((SurfaceView) streamView).getHolder().setFormat(PixelFormat.RGBX_8888);
        }
    }

    // ------------------------------------------------------------------------------------
    // GPU stage lifecycle
    // ------------------------------------------------------------------------------------

    /**
     * Starts the GPU stage on the stream's surface (replacing a previous one, if any).
     *
     * @return the surface the decoder must render into, or null to let the decoder render
     *         directly (stage unavailable or HDR stream); the stream works either way
     */
    public Surface attachOutput(SurfaceHolder holder, int videoWidth, int videoHeight, int framePacing) {
        releaseRenderer();
        if (hdrStream) {
            unavailableReason = "HDR";
            showOsd(activity.getString(R.string.sf_osd_hdr), true);
            return null;
        }
        GradingRenderer r;
        try {
            r = GradingRenderer.create(holder.getSurface(), videoWidth, videoHeight, toRendererPacing(framePacing));
        } catch (RuntimeException e) {
            LimeLog.warning("SelfFramed grading: GPU stage unavailable, rendering directly: " + e);
            unavailableReason = String.valueOf(e.getMessage());
            showOsd(activity.getString(R.string.sf_osd_unavailable, unavailableReason), true);
            return null;
        }
        unavailableReason = null;
        r.setListener(new GradingRenderer.Listener() {
            @Override
            public void onFirstFrame() {
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        showStartNotice();
                    }
                });
            }

            @Override
            public void onFailure(final String reason) {
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        unavailableReason = reason;
                        showOsd(activity.getString(R.string.sf_osd_unavailable, reason), true);
                    }
                });
            }
        });
        renderer = r;
        applyCurrent();
        return r.getInputSurface();
    }

    /**
     * Release hook for the decoder: runs on the decoder's cleanup thread after the decoder
     * let go of its output surface.
     */
    public Runnable rendererReleaseHook() {
        final GradingRenderer r = renderer;
        return new Runnable() {
            @Override
            public void run() {
                if (r != null) {
                    r.release();
                }
            }
        };
    }

    /** SurfaceHolder.Callback.surfaceDestroyed(): stop drawing before the surface goes away. */
    public void detachOutput() {
        GradingRenderer r = renderer;
        if (r != null) {
            r.detachOutput();
        }
    }

    /** Releases the GPU stage (it can be attached again later). */
    public void releaseRenderer() {
        GradingRenderer r = renderer;
        renderer = null;
        if (r != null) {
            r.release();
        }
    }

    /** Activity.onDestroy(). */
    public void onDestroy() {
        destroyed = true;
        uiHandler.removeCallbacksAndMessages(null);
        battery.release();
        store.save();
        releaseRenderer();
    }

    /** Extra line for the performance overlay, or null. */
    public String perfOverlayLine() {
        GradingRenderer r = renderer;
        if (unavailableReason != null) {
            return activity.getString(R.string.sf_perf_off, unavailableReason);
        }
        if (r != null) {
            String stats = r.getStatsLine();
            return activity.getString(R.string.sf_perf_line, stats.isEmpty() ? "…" : stats, describeState());
        }
        return null;
    }

    private boolean isStageActive() {
        return renderer != null && unavailableReason == null;
    }

    private static int toRendererPacing(int framePacing) {
        switch (framePacing) {
            case PreferenceConfiguration.FRAME_PACING_BALANCED:
                return GradingRenderer.PACING_VSYNC;
            case PreferenceConfiguration.FRAME_PACING_CAP_FPS:
            case PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS:
                return GradingRenderer.PACING_NEVER_DROP;
            case PreferenceConfiguration.FRAME_PACING_MIN_LATENCY:
            default:
                return GradingRenderer.PACING_LOWEST_LATENCY;
        }
    }

    private void applyCurrent() {
        GradingRenderer r = renderer;
        if (r == null) {
            return;
        }
        GradingPreset p = store.bundle().effectivePreset();
        r.setLut(p == null ? GradingLut.NEUTRAL : GradingLut.compile(p));
    }

    private void scheduleSave() {
        uiHandler.removeCallbacks(saveNow);
        uiHandler.postDelayed(saveNow, SAVE_DELAY_MS);
    }

    // ------------------------------------------------------------------------------------
    // Keyboard
    // ------------------------------------------------------------------------------------

    /** True if Ctrl+Alt+Shift+keyCode is a picture hotkey. */
    public boolean isHotkey(int keyCode) {
        return GradingHotkeys.actionFor(keyCode) != GradingHotkeys.ACTION_NONE;
    }

    /** Ctrl+Alt+Shift+keyCode was pressed (key down). Auto-repeat is ignored. */
    public void onHotkey(int keyCode) {
        if (lastKeyWasRepeat || destroyed) {
            return;
        }
        if (GradingHotkeys.actionFor(keyCode) == GradingHotkeys.ACTION_BATTERY) {
            // Works without the GPU stage, too
            battery.onHotkey();
            return;
        }
        if (!isStageActive()) {
            // Say why nothing happens instead of pretending to switch presets
            String reason = unavailableReason != null ? unavailableReason : "…";
            showOsd(hdrStream ? activity.getString(R.string.sf_osd_hdr)
                    : activity.getString(R.string.sf_osd_unavailable, reason), true);
            return;
        }
        PresetBundle b = store.bundle();
        switch (GradingHotkeys.actionFor(keyCode)) {
            case GradingHotkeys.ACTION_SLOT:
                selectSlot(GradingHotkeys.slotFor(keyCode));
                break;
            case GradingHotkeys.ACTION_TOGGLE_FILTER:
                if (b.isFilterEnabled() && b.getActiveSlot() != PresetBundle.NEUTRAL_SLOT) {
                    setFilterEnabled(false);
                } else if (b.getActiveSlot() == PresetBundle.NEUTRAL_SLOT) {
                    selectSlot(lastPresetSlot);
                } else {
                    setFilterEnabled(true);
                }
                break;
            case GradingHotkeys.ACTION_TOGGLE_PANEL:
                if (panelOpen) {
                    closePanel();
                } else {
                    openPanel();
                    releaseMouseWhenComboEnds = panelOpen && streamHost.isInputGrabbed();
                }
                break;
            case GradingHotkeys.ACTION_NEXT_SLOT:
                selectSlot(GradingHotkeys.cycle(b.getActiveSlot(), 1));
                break;
            case GradingHotkeys.ACTION_PREVIOUS_SLOT:
                selectSlot(GradingHotkeys.cycle(b.getActiveSlot(), -1));
                break;
            default:
                break;
        }
    }

    /**
     * Game: the Ctrl+Alt+Shift combo of a picture hotkey is over (all modifiers released).
     * Frees the mouse for a panel opened by that combo, delayed like upstream's Z so the last
     * modifier key-up still reaches the host.
     */
    public void onHotkeyComboReleased() {
        if (releaseMouseWhenComboEnds && panelOpen) {
            releaseMouseWhenComboEnds = false;
            uiHandler.removeCallbacks(releaseMouse);
            uiHandler.postDelayed(releaseMouse, MOUSE_RELEASE_DELAY_MS);
        }
    }

    /**
     * Sees every key event before the stream does. While the panel is open it takes the keys
     * it needs: every key-down except modifiers, Ctrl+Alt+Shift combos (stream hotkeys) and
     * game controller buttons, plus the matching key-ups. Everything else flows on to the
     * stream, so keys held before the panel opened are released on the host normally.
     *
     * @return true if the event was consumed
     */
    public boolean handleKeyEvent(KeyEvent event) {
        int action = event.getAction();
        int code = event.getKeyCode();
        if (action == KeyEvent.ACTION_DOWN) {
            lastKeyWasRepeat = event.getRepeatCount() > 0;
        }
        if (!panelOpen || panel == null) {
            return false;
        }
        if (KeyEvent.isModifierKey(code) || isGameController(event)) {
            return false;
        }
        if (action == KeyEvent.ACTION_DOWN) {
            if (event.isCtrlPressed() && event.isAltPressed() && event.isShiftPressed()) {
                return false;
            }
            panelKeysDown.add(code);
            panel.handleKeyEvent(event);
            return true;
        }
        if (action == KeyEvent.ACTION_UP && panelKeysDown.remove(code)) {
            panel.handleKeyEvent(event);
            return true;
        }
        return false;
    }

    private static boolean isGameController(KeyEvent event) {
        int source = event.getSource();
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || KeyEvent.isGamepadButton(event.getKeyCode());
    }

    public boolean isPanelOpen() {
        return panelOpen;
    }

    private void openPanel() {
        if (panelOpen || destroyed) {
            return;
        }
        if (panel == null) {
            panel = new GradingPanelView(activity, this);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.END | Gravity.TOP);
            overlayParent.addView(panel.view(), lp);
        }
        uiHandler.removeCallbacks(hideOsd);
        hideOsd.run();

        panelKeysDown.clear();
        panelOpen = true;
        panel.view().setVisibility(View.VISIBLE);
        panel.view().bringToFront();
        panel.refresh();
        panel.onShown();
    }

    // ------------------------------------------------------------------------------------
    // GradingEditorHost
    // ------------------------------------------------------------------------------------

    @Override
    public PresetBundle bundle() {
        return store.bundle();
    }

    @Override
    public int editSlot() {
        int active = store.bundle().getActiveSlot();
        return PresetBundle.isPresetSlot(active) ? active : lastPresetSlot;
    }

    @Override
    public void selectSlot(int slot) {
        PresetBundle b = store.bundle();
        b.setActiveSlot(slot);
        if (PresetBundle.isPresetSlot(slot)) {
            lastPresetSlot = slot;
            b.setFilterEnabled(true);
        }
        applyCurrent();
        scheduleSave();
        notifyStateChanged();
    }

    @Override
    public void presetChanged(int slot) {
        PresetBundle b = store.bundle();
        if (b.getActiveSlot() != slot) {
            b.setActiveSlot(slot);
        }
        lastPresetSlot = slot;
        b.setFilterEnabled(true);
        applyCurrent();
        scheduleSave();
    }

    @Override
    public boolean isFilterEnabled() {
        return store.bundle().isFilterEnabled();
    }

    @Override
    public void setFilterEnabled(boolean enabled) {
        store.bundle().setFilterEnabled(enabled);
        applyCurrent();
        scheduleSave();
        if (panelOpen) {
            panel.refresh();
        } else if (enabled) {
            showOsd(activity.getString(R.string.sf_osd_filter_on, describeState()), false);
        } else {
            showOsd(describeState(), false);
        }
    }

    @Override
    public GradingPreset factoryPreset(int slot) {
        return store.builtins()[slot - 1];
    }

    @Override
    public void closePanel() {
        if (!panelOpen) {
            return;
        }
        panelOpen = false;
        releaseMouseWhenComboEnds = false;
        uiHandler.removeCallbacks(releaseMouse);
        // Key-ups of keys still held go to the stream as stray key-ups, which hosts ignore
        panelKeysDown.clear();
        panel.onHidden();
        panel.view().setVisibility(View.GONE);
        // Focus first: captured pointer events are delivered to the focused view
        streamView.requestFocus();
        if (mouseReleasedByPanel) {
            mouseReleasedByPanel = false;
            if (!streamHost.isInputGrabbed()) {
                streamHost.setInputGrabbed(true);
            }
        }
        uiHandler.removeCallbacks(saveNow);
        store.save();
    }

    // ------------------------------------------------------------------------------------
    // On-screen notice (e-ink: black on white, thick border, no transparency, no animation)
    // ------------------------------------------------------------------------------------

    private void notifyStateChanged() {
        if (panelOpen) {
            panel.refresh();
        } else {
            showOsd(describeState(), false);
        }
    }

    private void showStartNotice() {
        if (destroyed || panelOpen) {
            return;
        }
        // E-ink: every notice costs a refresh. After the first few streams it is opt-in.
        SharedPreferences ui = activity.getSharedPreferences(UI_PREFS, Activity.MODE_PRIVATE);
        int shown = ui.getInt(KEY_START_NOTICES, 0);
        boolean always = PreferenceManager.getDefaultSharedPreferences(activity).getBoolean(PREF_START_NOTICE, false);
        if (!always && shown >= START_NOTICE_LIMIT) {
            return;
        }
        if (shown < START_NOTICE_LIMIT) {
            ui.edit().putInt(KEY_START_NOTICES, shown + 1).apply();
        }
        PresetBundle b = store.bundle();
        String text = describeState();
        if (b.effectivePreset() == null) {
            text += "\n" + activity.getString(R.string.sf_osd_start_hint);
        }
        showOsd(text, false);
    }

    /** Human-readable state, e.g. "3 · Paper White (Text)". */
    private String describeState() {
        PresetBundle b = store.bundle();
        int active = b.getActiveSlot();
        if (active == PresetBundle.NEUTRAL_SLOT) {
            return activity.getString(R.string.sf_osd_neutral);
        }
        if (!b.isFilterEnabled()) {
            return activity.getString(R.string.sf_osd_filter_off);
        }
        return activity.getString(R.string.sf_osd_preset, active, b.slot(active).getName());
    }

    private void showOsd(String text, boolean always) {
        if ((!osdEnabled && !always) || destroyed) {
            return;
        }
        if (osdView == null) {
            osdView = new TextView(activity);
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.WHITE);
            background.setStroke(dp(3), Color.BLACK);
            osdView.setBackground(background);
            osdView.setTextColor(Color.BLACK);
            osdView.setTypeface(Typeface.DEFAULT_BOLD);
            osdView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            osdView.setGravity(Gravity.CENTER);
            osdView.setPadding(dp(20), dp(12), dp(20), dp(12));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = dp(24);
            overlayParent.addView(osdView, lp);
        }
        osdView.setText(text);
        osdView.setVisibility(View.VISIBLE);
        osdView.bringToFront();
        uiHandler.removeCallbacks(hideOsd);
        uiHandler.postDelayed(hideOsd, OSD_DURATION_MS);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics()));
    }
}
