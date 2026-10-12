package com.limelight.selfframed.grading;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.selfframed.ui.GradingPanelView;

/**
 * Glue between the stream activity (Game) and the picture feature: owns the GPU stage,
 * the preset store, the hotkeys, the on-screen notice and the in-stream panel.
 *
 * Game only talks to this class, through the hooks marked [SelfFramed]. Everything here runs
 * on the UI thread unless stated otherwise.
 */
public final class GradingController implements GradingEditorHost {
    /** Master switch (settings). Off = exact upstream behaviour, this class is not created. */
    public static final String PREF_ENABLED = "sf_grading_enabled";
    /** Show the short notice when the preset changes. */
    public static final String PREF_OSD = "sf_grading_osd";

    private static final int OSD_DURATION_MS = 1800;
    private static final int SAVE_DELAY_MS = 400;

    /** What the controller needs from the stream activity. */
    public interface StreamHost {
        boolean isInputGrabbed();

        /** Captures (true) or releases (false) mouse and keyboard, like Ctrl+Alt+Shift+Z. */
        void setInputGrabbed(boolean grabbed);
    }

    private final Activity activity;
    private final FrameLayout overlayParent;
    private final View streamView;
    private final StreamHost streamHost;
    private final boolean hdrStream;
    private final boolean osdEnabled;
    private final PresetStore store;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private volatile GradingRenderer renderer;
    private String unavailableReason;
    private GradingPanel panel;
    private boolean panelOpen;
    private boolean grabbedBeforePanel;
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
    public static GradingController createForPreview(Activity activity, FrameLayout overlayParent, View view,
                                                     StreamHost streamHost) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        return new GradingController(activity, overlayParent, view, false, streamHost,
                prefs.getBoolean(PREF_OSD, true));
    }

    private GradingController(Activity activity, FrameLayout overlayParent, View streamView, boolean hdrStream,
                              StreamHost streamHost, boolean osdEnabled) {
        this.activity = activity;
        this.overlayParent = overlayParent;
        this.streamView = streamView;
        this.hdrStream = hdrStream;
        this.streamHost = streamHost;
        this.osdEnabled = osdEnabled;
        this.store = new PresetStore(activity);
        int active = store.bundle().getActiveSlot();
        this.lastPresetSlot = PresetBundle.isPresetSlot(active) ? active : 1;
    }

    // ------------------------------------------------------------------------------------
    // GPU stage lifecycle
    // ------------------------------------------------------------------------------------

    /**
     * Starts the GPU stage on the stream's surface.
     *
     * @return the surface the decoder must render into, or null to let the decoder render
     *         directly (stage unavailable or HDR stream); the stream works either way
     */
    public Surface attachOutput(SurfaceHolder holder, int videoWidth, int videoHeight, int framePacing) {
        if (hdrStream) {
            unavailableReason = "HDR";
            showOsd(activity.getString(R.string.sf_osd_hdr), true);
            return null;
        }
        try {
            GradingRenderer r = GradingRenderer.create(holder.getSurface(), videoWidth, videoHeight,
                    toRendererPacing(framePacing));
            r.setFirstFrameListener(new Runnable() {
                @Override
                public void run() {
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            showStartNotice();
                        }
                    });
                }
            });
            renderer = r;
        } catch (RuntimeException e) {
            LimeLog.warning("SelfFramed grading: GPU stage unavailable, rendering directly: " + e);
            unavailableReason = String.valueOf(e.getMessage());
            showOsd(activity.getString(R.string.sf_osd_unavailable, unavailableReason), true);
            return null;
        }
        applyCurrent();
        return renderer.getInputSurface();
    }

    /**
     * Release hook for the decoder: runs on the decoder's cleanup thread after the decoder
     * let go of its output surface.
     */
    public Runnable rendererReleaseHook() {
        return new Runnable() {
            @Override
            public void run() {
                GradingRenderer r = renderer;
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

    /** Activity.onDestroy(). */
    public void onDestroy() {
        destroyed = true;
        uiHandler.removeCallbacksAndMessages(null);
        store.save();
        GradingRenderer r = renderer;
        if (r != null) {
            r.release();
        }
    }

    /** Extra line for the performance overlay, or null. */
    public String perfOverlayLine() {
        GradingRenderer r = renderer;
        if (r != null) {
            String stats = r.getStatsLine();
            return activity.getString(R.string.sf_perf_line, stats.isEmpty() ? "…" : stats, describeState());
        }
        if (unavailableReason != null) {
            return activity.getString(R.string.sf_perf_off, unavailableReason);
        }
        return null;
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
     * Sees every key event before the stream does. While the panel is open it owns the
     * keyboard, except for modifier keys and Ctrl+Alt+Shift combos (stream hotkeys).
     *
     * @return true if the event was consumed
     */
    public boolean handleKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            lastKeyWasRepeat = event.getRepeatCount() > 0;
        }
        if (!panelOpen || panel == null) {
            return false;
        }
        if (KeyEvent.isModifierKey(event.getKeyCode())) {
            return false;
        }
        if (event.isCtrlPressed() && event.isAltPressed() && event.isShiftPressed()) {
            return false;
        }
        panel.handleKeyEvent(event);
        // Consume everything else so nothing leaks to the host or to Android (e.g. Back)
        return true;
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

        // Free the mouse so the panel can be used with it; restored on close
        grabbedBeforePanel = streamHost.isInputGrabbed();
        if (grabbedBeforePanel) {
            streamHost.setInputGrabbed(false);
        }

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
        panel.onHidden();
        panel.view().setVisibility(View.GONE);
        if (grabbedBeforePanel) {
            streamHost.setInputGrabbed(true);
        }
        streamView.requestFocus();
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
