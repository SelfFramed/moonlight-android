package com.limelight.selfframed.grading;

import java.util.Arrays;

/**
 * A tone curve defined by control points in [0,1]x[0,1], interpolated with a
 * shape-preserving (monotone) cubic Hermite spline.
 *
 * This is a 1:1 port of curveFunction()/validateCurve() from Martin's
 * game-curves.js / discord-stream-filter userscripts, so curves behave
 * identically in the stream and in the browser. Do not "improve" the math
 * without updating the reference tests (ToneCurveTest).
 *
 * Instances are immutable.
 */
public final class ToneCurve {
    public static final int MIN_POINTS = 2;
    public static final int MAX_POINTS = 32;
    /** Minimum horizontal distance between neighbouring control points. */
    public static final double MIN_SPACING = 0.015;

    public static final ToneCurve IDENTITY = new ToneCurve(new double[][] {{0, 0}, {1, 1}});

    private final double[] xs;
    private final double[] ys;
    private final double[] h;
    private final double[] m;

    private ToneCurve(double[][] points) {
        int n = points.length;
        xs = new double[n];
        ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = points[i][0];
            ys[i] = points[i][1];
        }

        h = new double[n - 1];
        double[] d = new double[n - 1];
        for (int i = 0; i < n - 1; i++) {
            h[i] = xs[i + 1] - xs[i];
            d[i] = (ys[i + 1] - ys[i]) / h[i];
        }

        m = new double[n];
        m[0] = d[0];
        m[n - 1] = d[n - 2];
        for (int i = 1; i < n - 1; i++) {
            double a = d[i - 1], b = d[i];
            double w1 = 2 * h[i] + h[i - 1], w2 = h[i] + 2 * h[i - 1];
            m[i] = a * b <= 0 ? 0 : (w1 + w2) / (w1 / a + w2 / b);
        }
    }

    /**
     * Creates a curve from control points. Invalid input yields IDENTITY, exactly like
     * validateCurve() in the userscripts: 2..32 points, all finite and in [0,1], first x == 0,
     * last x == 1, x strictly increasing with at least MIN_SPACING between neighbours.
     */
    public static ToneCurve of(double[][] points) {
        if (!isValid(points)) {
            return IDENTITY;
        }
        double[][] copy = new double[points.length][];
        for (int i = 0; i < points.length; i++) {
            copy[i] = new double[] {points[i][0], points[i][1]};
        }
        return new ToneCurve(copy);
    }

    public static boolean isValid(double[][] points) {
        if (points == null || points.length < MIN_POINTS || points.length > MAX_POINTS) {
            return false;
        }
        for (double[] p : points) {
            if (p == null || p.length != 2) {
                return false;
            }
            for (double v : p) {
                if (Double.isNaN(v) || Double.isInfinite(v) || v < 0 || v > 1) {
                    return false;
                }
            }
        }
        if (points[0][0] != 0 || points[points.length - 1][0] != 1) {
            return false;
        }
        for (int i = 1; i < points.length; i++) {
            if (points[i][0] - points[i - 1][0] < MIN_SPACING) {
                return false;
            }
        }
        return true;
    }

    /** Evaluates the curve; input and output are clamped to [0,1]. */
    public double apply(double x) {
        x = Math.max(0, Math.min(1, x));
        int n = xs.length;
        int i = 0;
        while (i < n - 2 && x > xs[i + 1]) {
            i++;
        }
        double t = (x - xs[i]) / h[i], t2 = t * t, t3 = t2 * t;
        double y = (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h[i] * m[i]
                + (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h[i] * m[i + 1];
        return Math.max(0, Math.min(1, y));
    }

    public int size() {
        return xs.length;
    }

    public double x(int i) {
        return xs[i];
    }

    public double y(int i) {
        return ys[i];
    }

    /** Returns a fresh copy of the control points. */
    public double[][] points() {
        double[][] out = new double[xs.length][];
        for (int i = 0; i < xs.length; i++) {
            out[i] = new double[] {xs[i], ys[i]};
        }
        return out;
    }

    public boolean isIdentity() {
        for (int i = 0; i < xs.length; i++) {
            if (Math.abs(xs[i] - ys[i]) > 0.000001) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ToneCurve)) {
            return false;
        }
        ToneCurve other = (ToneCurve) o;
        return Arrays.equals(xs, other.xs) && Arrays.equals(ys, other.ys);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(xs) + Arrays.hashCode(ys);
    }
}
