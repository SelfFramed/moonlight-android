package com.limelight.selfframed.grading;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One image adjustment preset.
 *
 * Field semantics and value ranges are identical to Martin's userscripts
 * (game-curves.js / discord-stream-filter): brightness/contrast/saturation in percent,
 * gamma as an exponent divisor (out = in^(1/gamma), so gamma > 1 lifts shadows), and four
 * tone curves (master, r, g, b). The JSON format is a superset of the userscripts' saved
 * state, so such a state can be imported directly.
 *
 * Added for the e-ink panel: an optional grayscale mix ("mono") that converts colour to
 * gray with adjustable channel weights before the tone curves are applied. The defaults
 * match the Glider's own conversion (BT.601: 77/150/29 of 256).
 *
 * Instances are mutable and must only be touched on the UI thread. The renderer only ever
 * sees immutable {@link GradingLut} snapshots compiled from a preset.
 */
public final class GradingPreset {
    public static final int BRIGHTNESS_MIN = 50, BRIGHTNESS_MAX = 200;
    public static final int CONTRAST_MIN = 50, CONTRAST_MAX = 200;
    public static final int SATURATION_MIN = 0, SATURATION_MAX = 200;
    public static final double GAMMA_MIN = 0.4, GAMMA_MAX = 3.0;
    public static final int NAME_MAX_LENGTH = 40;

    public static final double MONO_DEFAULT_R = 0.299, MONO_DEFAULT_G = 0.587, MONO_DEFAULT_B = 0.114;

    public static final String CURVE_MASTER = "master";
    public static final String CURVE_R = "r";
    public static final String CURVE_G = "g";
    public static final String CURVE_B = "b";
    public static final String[] CURVE_KEYS = {CURVE_MASTER, CURVE_R, CURVE_G, CURVE_B};

    private String name;
    private int brightness = 100;
    private int contrast = 100;
    private int saturation = 100;
    private double gamma = 1.0;
    private ToneCurve master = ToneCurve.IDENTITY;
    private ToneCurve curveR = ToneCurve.IDENTITY;
    private ToneCurve curveG = ToneCurve.IDENTITY;
    private ToneCurve curveB = ToneCurve.IDENTITY;
    private boolean mono;
    private double monoR = MONO_DEFAULT_R, monoG = MONO_DEFAULT_G, monoB = MONO_DEFAULT_B;

    public GradingPreset(String name) {
        setName(name);
    }

    public static GradingPreset neutral(String name) {
        return new GradingPreset(name);
    }

    public GradingPreset copy() {
        GradingPreset p = new GradingPreset(name);
        p.brightness = brightness;
        p.contrast = contrast;
        p.saturation = saturation;
        p.gamma = gamma;
        p.master = master;
        p.curveR = curveR;
        p.curveG = curveG;
        p.curveB = curveB;
        p.mono = mono;
        p.monoR = monoR;
        p.monoG = monoG;
        p.monoB = monoB;
        return p;
    }

    // ---- Accessors (setters clamp to the valid range) ----

    public String getName() {
        return name;
    }

    public void setName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.length() > NAME_MAX_LENGTH) {
            n = n.substring(0, NAME_MAX_LENGTH);
        }
        this.name = n;
    }

    public int getBrightness() {
        return brightness;
    }

    public void setBrightness(int v) {
        brightness = clamp(v, BRIGHTNESS_MIN, BRIGHTNESS_MAX);
    }

    public int getContrast() {
        return contrast;
    }

    public void setContrast(int v) {
        contrast = clamp(v, CONTRAST_MIN, CONTRAST_MAX);
    }

    public int getSaturation() {
        return saturation;
    }

    public void setSaturation(int v) {
        saturation = clamp(v, SATURATION_MIN, SATURATION_MAX);
    }

    public double getGamma() {
        return gamma;
    }

    public void setGamma(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            v = 1.0;
        }
        // Keep the 0.05 grid of the userscript slider so values stay comparable
        gamma = Math.round(Math.max(GAMMA_MIN, Math.min(GAMMA_MAX, v)) * 100.0) / 100.0;
    }

    public ToneCurve getCurve(String key) {
        switch (key) {
            case CURVE_R:
                return curveR;
            case CURVE_G:
                return curveG;
            case CURVE_B:
                return curveB;
            case CURVE_MASTER:
            default:
                return master;
        }
    }

    public void setCurve(String key, ToneCurve curve) {
        if (curve == null) {
            curve = ToneCurve.IDENTITY;
        }
        switch (key) {
            case CURVE_R:
                curveR = curve;
                break;
            case CURVE_G:
                curveG = curve;
                break;
            case CURVE_B:
                curveB = curve;
                break;
            case CURVE_MASTER:
            default:
                master = curve;
                break;
        }
    }

    public boolean isMono() {
        return mono;
    }

    public void setMono(boolean mono) {
        this.mono = mono;
    }

    public double getMonoR() {
        return monoR;
    }

    public double getMonoG() {
        return monoG;
    }

    public double getMonoB() {
        return monoB;
    }

    public void setMonoWeights(double r, double g, double b) {
        monoR = clampUnit(r, MONO_DEFAULT_R);
        monoG = clampUnit(g, MONO_DEFAULT_G);
        monoB = clampUnit(b, MONO_DEFAULT_B);
    }

    /** True if this preset leaves the picture untouched. */
    public boolean isNeutral() {
        return brightness == 100 && contrast == 100 && saturation == 100
                && Math.abs(gamma - 1.0) <= 0.001 && !mono
                && master.isIdentity() && curveR.isIdentity() && curveG.isIdentity() && curveB.isIdentity();
    }

    /** Resets all image parameters (keeps the name). */
    public void resetValues() {
        brightness = 100;
        contrast = 100;
        saturation = 100;
        gamma = 1.0;
        master = curveR = curveG = curveB = ToneCurve.IDENTITY;
        mono = false;
        monoR = MONO_DEFAULT_R;
        monoG = MONO_DEFAULT_G;
        monoB = MONO_DEFAULT_B;
    }

    // ---- JSON ----

    public JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("brightness", brightness);
            o.put("contrast", contrast);
            o.put("saturation", saturation);
            o.put("gamma", gamma);
            JSONObject curves = new JSONObject();
            for (String key : CURVE_KEYS) {
                curves.put(key, curveToJson(getCurve(key)));
            }
            o.put("curves", curves);
            JSONObject m = new JSONObject();
            m.put("enabled", mono);
            m.put("r", monoR);
            m.put("g", monoG);
            m.put("b", monoB);
            o.put("mono", m);
            return o;
        } catch (JSONException e) {
            // Only thrown for non-finite numbers, which the setters rule out
            throw new IllegalStateException(e);
        }
    }

    /**
     * Lenient parser: unknown keys are ignored, missing or invalid values fall back to
     * neutral defaults (invalid curves become identity, like validateCurve()).
     */
    public static GradingPreset fromJson(JSONObject o, String fallbackName) {
        GradingPreset p = new GradingPreset(o.optString("name", fallbackName));
        if (p.name.isEmpty()) {
            p.setName(fallbackName);
        }
        p.setBrightness((int) Math.round(optFinite(o, "brightness", 100)));
        p.setContrast((int) Math.round(optFinite(o, "contrast", 100)));
        p.setSaturation((int) Math.round(optFinite(o, "saturation", 100)));
        p.setGamma(optFinite(o, "gamma", 1.0));
        JSONObject curves = o.optJSONObject("curves");
        if (curves != null) {
            for (String key : CURVE_KEYS) {
                p.setCurve(key, curveFromJson(curves.optJSONArray(key)));
            }
        }
        JSONObject m = o.optJSONObject("mono");
        if (m != null) {
            p.setMono(m.optBoolean("enabled", false));
            p.setMonoWeights(optFinite(m, "r", MONO_DEFAULT_R), optFinite(m, "g", MONO_DEFAULT_G),
                    optFinite(m, "b", MONO_DEFAULT_B));
        }
        return p;
    }

    static JSONArray curveToJson(ToneCurve curve) throws JSONException {
        JSONArray arr = new JSONArray();
        // No rounding here: it could push neighbours below MIN_SPACING and the curve would be
        // rejected (reset to identity) on the next load.
        for (int i = 0; i < curve.size(); i++) {
            JSONArray pt = new JSONArray();
            pt.put(curve.x(i));
            pt.put(curve.y(i));
            arr.put(pt);
        }
        return arr;
    }

    static ToneCurve curveFromJson(JSONArray arr) {
        if (arr == null || arr.length() < ToneCurve.MIN_POINTS || arr.length() > ToneCurve.MAX_POINTS) {
            return ToneCurve.IDENTITY;
        }
        double[][] pts = new double[arr.length()][];
        for (int i = 0; i < arr.length(); i++) {
            JSONArray pt = arr.optJSONArray(i);
            if (pt == null || pt.length() != 2) {
                return ToneCurve.IDENTITY;
            }
            double x = pt.optDouble(0, Double.NaN);
            double y = pt.optDouble(1, Double.NaN);
            pts[i] = new double[] {x, y};
        }
        return ToneCurve.of(pts);
    }

    private static double optFinite(JSONObject o, String key, double fallback) {
        double v = o.optDouble(key, fallback);
        return Double.isNaN(v) || Double.isInfinite(v) ? fallback : v;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clampUnit(double v, double fallback) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return fallback;
        }
        return Math.max(0, Math.min(1, v));
    }
}
