package com.limelight.selfframed.ui;

import com.limelight.selfframed.grading.PresetBundle;

/**
 * Small value rules of the picture panel that do not need Android (unit-tested on the JVM,
 * PanelValuesTest).
 */
final class PanelValues {
    /** Step of the brightness, contrast and saturation rows (percent). */
    static final int PERCENT_STEP = 1, PERCENT_STEP_COARSE = 10;
    static final double GAMMA_STEP = 0.05, GAMMA_STEP_COARSE = 0.25;
    static final double WEIGHT_STEP = 0.01, WEIGHT_STEP_COARSE = 0.1;

    private PanelValues() {
    }

    /** Next (+1) or previous (-1) preset slot, cycling within 1..9 (the panel never selects neutral this way). */
    static int cyclePresetSlot(int slot, int direction) {
        int n = PresetBundle.SLOT_COUNT;
        int zeroBased = PresetBundle.isPresetSlot(slot) ? slot - 1 : 0;
        return ((zeroBased + direction) % n + n) % n + 1;
    }

    /**
     * A grayscale weight moved by {@code delta}, clamped to [0, 1] and rounded to three
     * decimals so repeated steps do not accumulate floating-point noise (the defaults
     * 0.299 / 0.587 / 0.114 stay on that grid).
     */
    static double stepWeight(double weight, double delta) {
        double v = Math.round((weight + delta) * 1000.0) / 1000.0;
        return Math.max(0, Math.min(1, v));
    }

    /**
     * The weights as whole percentages of their sum (what the renderer actually uses),
     * rounded with the largest-remainder method so the three always add up to 100.
     *
     * @return false (and {@code out} untouched) if all weights are zero; the renderer then
     *         falls back to the default weights
     */
    static boolean weightPercents(double r, double g, double b, int[] out) {
        double sum = r + g + b;
        if (sum < 1e-6) {
            return false;
        }
        double[] exact = {r / sum * 100, g / sum * 100, b / sum * 100};
        int total = 0;
        for (int i = 0; i < 3; i++) {
            out[i] = (int) Math.floor(exact[i]);
            total += out[i];
        }
        while (total < 100) {
            int best = 0;
            double bestRemainder = -1;
            for (int i = 0; i < 3; i++) {
                double remainder = exact[i] - out[i];
                if (remainder > bestRemainder) {
                    best = i;
                    bestRemainder = remainder;
                }
            }
            out[best]++;
            total++;
        }
        return true;
    }

    /** A curve coordinate in [0, 1] as the 0..255 value shown on the axes. */
    static int toByte(double v) {
        return (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
    }
}
