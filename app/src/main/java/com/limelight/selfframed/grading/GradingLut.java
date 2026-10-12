package com.limelight.selfframed.grading;

/**
 * Immutable, renderer-ready snapshot of a {@link GradingPreset}.
 *
 * Processing order per pixel (identical to the userscripts' CSS/SVG filter chain, with the
 * e-ink grayscale mix in front):
 *
 *   [mono mix] -> gamma -> master curve -> channel curve -> brightness -> contrast -> saturation
 *
 * Everything from gamma to contrast is a per-channel function and is baked into a
 * 256-entry lookup table per channel (sampled with linear filtering on the GPU). The
 * grayscale mix and the saturation step mix channels, so they stay shader uniforms.
 * Every step clamps to [0,1], like SVG/CSS filter primitives do.
 */
public final class GradingLut {
    public static final int SIZE = 256;

    /** Luminance weights of the CSS saturate() matrix (Filter Effects spec). */
    public static final float SAT_WEIGHT_R = 0.213f, SAT_WEIGHT_G = 0.715f, SAT_WEIGHT_B = 0.072f;

    public static final GradingLut NEUTRAL = compile(GradingPreset.neutral(""));

    private final byte[] rgba;
    private final float saturation;
    private final boolean mono;
    private final float monoR, monoG, monoB;
    private final boolean neutral;

    private GradingLut(byte[] rgba, float saturation, boolean mono, float monoR, float monoG, float monoB,
                       boolean neutral) {
        this.rgba = rgba;
        this.saturation = saturation;
        this.mono = mono;
        this.monoR = monoR;
        this.monoG = monoG;
        this.monoB = monoB;
        this.neutral = neutral;
    }

    public static GradingLut compile(GradingPreset p) {
        byte[] rgba = new byte[SIZE * 4];
        for (int i = 0; i < SIZE; i++) {
            double x = i / (double) (SIZE - 1);
            rgba[i * 4] = toByte(evaluate(p, GradingPreset.CURVE_R, x));
            rgba[i * 4 + 1] = toByte(evaluate(p, GradingPreset.CURVE_G, x));
            rgba[i * 4 + 2] = toByte(evaluate(p, GradingPreset.CURVE_B, x));
            rgba[i * 4 + 3] = (byte) 0xFF;
        }

        float wr = (float) p.getMonoR(), wg = (float) p.getMonoG(), wb = (float) p.getMonoB();
        float sum = wr + wg + wb;
        if (sum < 1e-6f) {
            wr = (float) GradingPreset.MONO_DEFAULT_R;
            wg = (float) GradingPreset.MONO_DEFAULT_G;
            wb = (float) GradingPreset.MONO_DEFAULT_B;
            sum = wr + wg + wb;
        }

        return new GradingLut(rgba, p.getSaturation() / 100f, p.isMono(),
                wr / sum, wg / sum, wb / sum, p.isNeutral());
    }

    /**
     * Per-channel transfer function (gamma, master curve, channel curve, brightness,
     * contrast) for an input value in [0,1]. Exposed for tests and for drawing histograms
     * or previews; the renderer uses the baked table.
     */
    public static double evaluate(GradingPreset p, String channel, double x) {
        double v = Math.max(0, Math.min(1, x));
        double gamma = p.getGamma();
        if (Math.abs(gamma - 1.0) > 1e-9) {
            v = Math.pow(v, 1.0 / gamma);
        }
        v = p.getCurve(GradingPreset.CURVE_MASTER).apply(v);
        v = p.getCurve(channel).apply(v);
        v = clamp01(v * (p.getBrightness() / 100.0));
        v = clamp01((v - 0.5) * (p.getContrast() / 100.0) + 0.5);
        return v;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static byte toByte(double v) {
        return (byte) (int) Math.round(clamp01(v) * 255.0);
    }

    /** RGBA8 table of SIZE texels (R, G, B = per-channel output, A = 255). Do not modify. */
    public byte[] rgba() {
        return rgba;
    }

    /** Unsigned table value for a channel (0 = R, 1 = G, 2 = B) at index i. */
    public int value(int channel, int i) {
        return rgba[i * 4 + channel] & 0xFF;
    }

    public float saturation() {
        return saturation;
    }

    public boolean mono() {
        return mono;
    }

    public float monoR() {
        return monoR;
    }

    public float monoG() {
        return monoG;
    }

    public float monoB() {
        return monoB;
    }

    /** True if the snapshot does not change the picture at all. */
    public boolean isNeutral() {
        return neutral;
    }
}
