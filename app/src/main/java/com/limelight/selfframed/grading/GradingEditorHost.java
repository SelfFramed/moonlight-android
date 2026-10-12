package com.limelight.selfframed.grading;

/**
 * What the in-stream picture panel may do. Implemented by {@link GradingController}.
 * All methods must be called on the UI thread.
 */
public interface GradingEditorHost {
    /** The nine slots plus active slot and filter switch. Presets may be mutated in place. */
    PresetBundle bundle();

    /**
     * The slot the panel edits: the active slot, or the most recently active preset slot
     * while "neutral" (slot 0) is selected. Always 1..9.
     */
    int editSlot();

    /** Activates slot 0..9 live (0 = neutral) and shows the on-screen notice. */
    void selectSlot(int slot);

    /**
     * Call after every change to the preset in {@code slot} (mutated in place or replaced via
     * {@link PresetBundle#setSlot}). Applies it live, makes the slot active, switches the filter
     * on and persists asynchronously.
     */
    void presetChanged(int slot);

    boolean isFilterEnabled();

    /** Before/after toggle without changing the active slot. */
    void setFilterEnabled(boolean enabled);

    /** A fresh copy of the factory default for slot 1..9 (for "reset preset"). */
    GradingPreset factoryPreset(int slot);

    /** Closes the panel (Escape, close button). */
    void closePanel();
}
