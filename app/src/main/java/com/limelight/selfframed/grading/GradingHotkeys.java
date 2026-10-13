package com.limelight.selfframed.grading;

import android.view.KeyEvent;

/**
 * The fork's stream hotkeys (picture, plus B for the battery). Each one is pressed together with
 * Ctrl+Alt+Shift, Moonlight's modifier for client-side shortcuts (upstream already uses Z, Q and
 * C). The combo never reaches the host.
 *
 * To add a hotkey: add an action constant, map a key code in {@link #actionFor(int)}, handle
 * it in {@link GradingController#onHotkey(int)} and list it in docs/selfframed/BEDIENUNG.md.
 */
public final class GradingHotkeys {
    public static final int ACTION_NONE = 0;
    /** Activate slot {@link #slotFor(int)} (0 = neutral). */
    public static final int ACTION_SLOT = 1;
    /** Before/after: filter on/off without changing the slot (like Alt+Shift+F in the userscripts). */
    public static final int ACTION_TOGGLE_FILTER = 2;
    /** Open/close the picture panel (like Alt+Shift+P in the userscripts). */
    public static final int ACTION_TOGGLE_PANEL = 3;
    public static final int ACTION_NEXT_SLOT = 4;
    public static final int ACTION_PREVIOUS_SLOT = 5;
    /** Battery state on demand, or hide the critical battery notice until the next percent drop. */
    public static final int ACTION_BATTERY = 6;

    private GradingHotkeys() {
    }

    public static int actionFor(int keyCode) {
        if (slotFor(keyCode) >= 0) {
            return ACTION_SLOT;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_F:
                return ACTION_TOGGLE_FILTER;
            case KeyEvent.KEYCODE_P:
                return ACTION_TOGGLE_PANEL;
            case KeyEvent.KEYCODE_B:
                return ACTION_BATTERY;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return ACTION_NEXT_SLOT;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_PAGE_UP:
                return ACTION_PREVIOUS_SLOT;
            default:
                return ACTION_NONE;
        }
    }

    /** Slot 0..9 for the digit keys (top row and numpad), otherwise -1. */
    public static int slotFor(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return keyCode - KeyEvent.KEYCODE_0;
        }
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            return keyCode - KeyEvent.KEYCODE_NUMPAD_0;
        }
        return -1;
    }

    /** Next (direction +1) or previous (-1) slot in the cycle neutral, 1..9. */
    public static int cycle(int slot, int direction) {
        int n = PresetBundle.SLOT_COUNT + 1;
        return ((slot + direction) % n + n) % n;
    }
}
