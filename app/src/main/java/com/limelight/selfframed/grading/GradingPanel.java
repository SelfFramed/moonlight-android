package com.limelight.selfframed.grading;

import android.view.KeyEvent;
import android.view.View;

/**
 * Contract of the in-stream picture panel (implementation:
 * {@code com.limelight.selfframed.ui.GradingPanelView}). The controller adds {@link #view()}
 * on top of the stream, routes key events to it while it is open and calls
 * {@link #refresh()} whenever state changes from outside (hotkeys, import).
 */
public interface GradingPanel {
    View view();

    /** Re-read everything from the host (active slot, filter switch, preset values). */
    void refresh();

    /**
     * Key events while the panel is open, before they reach the stream. Return true if
     * consumed. Never consume modifier keys (Ctrl, Alt, Shift, Meta) and never consume
     * events while Ctrl+Alt+Shift are all held (those are stream hotkeys).
     */
    boolean handleKeyEvent(KeyEvent event);

    void onShown();

    void onHidden();
}
