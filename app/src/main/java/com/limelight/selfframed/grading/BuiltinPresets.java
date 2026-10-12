package com.limelight.selfframed.grading;

/**
 * Factory defaults for the nine preset slots, aimed at the SelfFramed e-ink lapdock
 * (ES133UE2 panel behind a Modos Glider: BT.601 luma, dithered to 16 gray levels).
 *
 * These are reasoned starting points, not measured optima. Tune them on the device and
 * record the results in docs/selfframed/PRESETS.md.
 */
public final class BuiltinPresets {
    /** Number of built-in definitions (slots 1..6); slots 7..9 start neutral. */
    public static final int DEFINED = 6;

    private BuiltinPresets() {
    }

    /**
     * Creates the default slot contents.
     *
     * @param names nine display names, index 0 = slot 1
     */
    public static GradingPreset[] create(String[] names) {
        if (names == null || names.length != PresetBundle.SLOT_COUNT) {
            throw new IllegalArgumentException("Need " + PresetBundle.SLOT_COUNT + " names");
        }
        GradingPreset[] slots = new GradingPreset[PresetBundle.SLOT_COUNT];

        // 1: Gentle lift for games and video. Values are the "careful starting point for
        // dark streams" from Martin's discord-stream-filter README (gamma 1.25, contrast 105 %).
        GradingPreset filmGame = new GradingPreset(names[0]);
        filmGame.setGamma(1.25);
        filmGame.setContrast(105);
        slots[0] = filmGame;

        // 2: Strong shadow lift for dark games, films and dark UIs (0.5 -> 0.65). Black stays
        // black: a lifted black point would only turn into dither noise on the panel.
        GradingPreset shadows = new GradingPreset(names[1]);
        shadows.setGamma(1.6);
        shadows.setContrast(105);
        slots[1] = shadows;

        // 3: Paper white for text: light grays become clean white (no dither noise on the
        // background), dark grays become black (crisper glyphs). Acts like levels with a
        // black point of 0.10 and a white point of 0.88 and soft shoulders.
        GradingPreset paper = new GradingPreset(names[2]);
        paper.setCurve(GradingPreset.CURVE_MASTER, ToneCurve.of(new double[][] {
                {0, 0}, {0.10, 0}, {0.88, 1}, {1, 1}}));
        slots[2] = paper;

        // 4: High contrast S-curve.
        GradingPreset contrast = new GradingPreset(names[3]);
        contrast.setCurve(GradingPreset.CURVE_MASTER, ToneCurve.of(new double[][] {
                {0, 0}, {0.25, 0.12}, {0.75, 0.88}, {1, 1}}));
        slots[3] = contrast;

        // 5: Near-threshold curve for crisp black/white text (pairs well with the Glider's
        // fast monochrome modes).
        GradingPreset threshold = new GradingPreset(names[4]);
        threshold.setCurve(GradingPreset.CURVE_MASTER, ToneCurve.of(new double[][] {
                {0, 0}, {0.40, 0.03}, {0.60, 0.97}, {1, 1}}));
        slots[4] = threshold;

        // 6: Inverted: turns dark themes into light ones.
        GradingPreset inverted = new GradingPreset(names[5]);
        inverted.setCurve(GradingPreset.CURVE_MASTER, ToneCurve.of(new double[][] {{0, 1}, {1, 0}}));
        slots[5] = inverted;

        // 7..9: free slots
        for (int i = DEFINED; i < PresetBundle.SLOT_COUNT; i++) {
            slots[i] = GradingPreset.neutral(names[i]);
        }
        return slots;
    }
}
