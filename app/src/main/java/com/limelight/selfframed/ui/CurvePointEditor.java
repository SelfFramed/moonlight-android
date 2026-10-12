package com.limelight.selfframed.ui;

import com.limelight.selfframed.grading.ToneCurve;

/**
 * Editing rules of the tone-curve editor: the control points of one curve plus a selected
 * point. Pure Java (no android.*), so the rules are unit-tested on the JVM
 * (CurvePointEditorTest).
 *
 * Invariant: the points always satisfy {@link ToneCurve#isValid(double[][])}. Every operation
 * clamps instead of failing, exactly like the drag rules of Martin's userscripts:
 * <ul>
 *   <li>the endpoints keep x = 0 and x = 1, only their y moves;</li>
 *   <li>an inner point's x stays within [previous + MIN_SPACING, next - MIN_SPACING];</li>
 *   <li>y stays within [0, 1];</li>
 *   <li>at most {@link ToneCurve#MAX_POINTS} points, never fewer than the two endpoints.</li>
 * </ul>
 * The spacing bounds are computed so that they also hold after floating-point subtraction
 * (ToneCurve.isValid() compares {@code x[i] - x[i-1] < MIN_SPACING}), otherwise a point
 * pushed against its neighbour could make the whole curve fall back to identity.
 *
 * Mutating methods return whether anything changed, so callers only apply and save real
 * changes.
 */
public final class CurvePointEditor {
    /** Arrow-key step (fraction of the full range), like the userscript. */
    public static final double STEP_FINE = 0.005;
    /** Arrow-key step with Shift. */
    public static final double STEP_COARSE = 0.04;

    private final double[] xs = new double[ToneCurve.MAX_POINTS];
    private final double[] ys = new double[ToneCurve.MAX_POINTS];
    private int size;
    private int selected;

    public CurvePointEditor() {
        load(ToneCurve.IDENTITY);
    }

    /**
     * Takes over the points of {@code curve}. The selected index is kept if it still exists,
     * otherwise it is clamped to the last point.
     */
    public void load(ToneCurve curve) {
        if (curve == null) {
            curve = ToneCurve.IDENTITY;
        }
        size = curve.size();
        for (int i = 0; i < size; i++) {
            xs[i] = curve.x(i);
            ys[i] = curve.y(i);
        }
        selected = Math.max(0, Math.min(selected, size - 1));
    }

    /**
     * The edited curve, or null if the points were invalid. Null is unreachable by
     * construction (the tests assert it); callers skip it instead of crashing the stream, and
     * it is never IDENTITY by accident (which ToneCurve.of() would return for invalid points).
     */
    public ToneCurve toCurve() {
        double[][] pts = points();
        return ToneCurve.isValid(pts) ? ToneCurve.of(pts) : null;
    }

    /** A fresh copy of the points (for tests and validation). */
    public double[][] points() {
        double[][] pts = new double[size][];
        for (int i = 0; i < size; i++) {
            pts[i] = new double[] {xs[i], ys[i]};
        }
        return pts;
    }

    public int size() {
        return size;
    }

    public double x(int i) {
        return xs[i];
    }

    public double y(int i) {
        return ys[i];
    }

    public int selected() {
        return selected;
    }

    public boolean isEndpoint(int i) {
        return i == 0 || i == size - 1;
    }

    /** True if the curve is the straight identity line (any number of points on it). */
    public boolean isIdentity() {
        for (int i = 0; i < size; i++) {
            if (Math.abs(xs[i] - ys[i]) > 0.000001) {
                return false;
            }
        }
        return true;
    }

    // ---- Selection ----

    /** Selects point {@code i} (clamped to the existing points). */
    public boolean select(int i) {
        int s = Math.max(0, Math.min(i, size - 1));
        if (s == selected) {
            return false;
        }
        selected = s;
        return true;
    }

    /** Selects the previous (-1) or next (+1) point; stops at the ends (no wrap-around). */
    public boolean selectRelative(int delta) {
        return select(selected + delta);
    }

    // ---- Moving ----

    /** Moves the selected point by (dx, dy), clamped (arrow keys). */
    public boolean moveSelectedBy(double dx, double dy) {
        return moveTo(selected, xs[selected] + dx, ys[selected] + dy);
    }

    /**
     * Moves point {@code i} as close to (x, y) as the rules allow (mouse drag). Endpoints
     * ignore x. NaN coordinates leave that coordinate unchanged.
     */
    public boolean moveTo(int i, double x, double y) {
        if (i < 0 || i >= size) {
            return false;
        }
        double nx = Double.isNaN(x) ? xs[i] : clampX(i, x);
        double ny = Double.isNaN(y) ? ys[i] : clamp01(y);
        if (nx == xs[i] && ny == ys[i]) {
            return false;
        }
        xs[i] = nx;
        ys[i] = ny;
        return true;
    }

    // ---- Inserting ----

    /**
     * Inserts a point on the current curve halfway between the selected point and the next
     * one (from the last point: halfway to the previous one; if that gap is too narrow, the
     * other side is tried). The new point becomes selected.
     *
     * @return index of the new point, or -1 if no gap is wide enough or the curve is full
     */
    public int insertNextToSelected() {
        if (size >= ToneCurve.MAX_POINTS) {
            return -1;
        }
        if (selected < size - 1) {
            int k = insertMidpoint(selected, selected + 1);
            if (k >= 0) {
                return k;
            }
        }
        if (selected > 0) {
            return insertMidpoint(selected - 1, selected);
        }
        return -1;
    }

    /**
     * Inserts a point at {@code x} lying on the current curve (y = curve(x)), so the shape
     * barely changes until the point is moved (mouse click on an empty spot). x is pulled in
     * if it is too close to a neighbour. The new point becomes selected.
     *
     * @return index of the new point, or -1 if the gap is too narrow or the curve is full
     */
    public int insertOnCurve(double x) {
        if (size >= ToneCurve.MAX_POINTS || Double.isNaN(x)) {
            return -1;
        }
        int k = 1;
        while (k < size - 1 && xs[k] <= x) {
            k++;
        }
        // The new point goes between k-1 and k
        double lo = minXAfter(xs[k - 1]);
        double hi = maxXBefore(xs[k]);
        if (lo > hi) {
            return -1;
        }
        double nx = Math.max(lo, Math.min(hi, x));
        double ny = clamp01(curveValue(nx));
        insertAt(k, nx, ny);
        return k;
    }

    private int insertMidpoint(int a, int b) {
        double mid = (xs[a] + xs[b]) / 2;
        if (mid < minXAfter(xs[a]) || mid > maxXBefore(xs[b])) {
            return -1;
        }
        double y = clamp01(curveValue(mid));
        insertAt(b, mid, y);
        return b;
    }

    private void insertAt(int k, double x, double y) {
        for (int i = size; i > k; i--) {
            xs[i] = xs[i - 1];
            ys[i] = ys[i - 1];
        }
        xs[k] = x;
        ys[k] = y;
        size++;
        selected = k;
    }

    // ---- Deleting / resetting ----

    /** Deletes the selected inner point and selects its left neighbour. Endpoints stay. */
    public boolean deleteSelected() {
        return delete(selected);
    }

    /** Deletes inner point {@code i}; the left neighbour becomes selected. */
    public boolean delete(int i) {
        if (i <= 0 || i >= size - 1 || size <= ToneCurve.MIN_POINTS) {
            return false;
        }
        for (int j = i; j < size - 1; j++) {
            xs[j] = xs[j + 1];
            ys[j] = ys[j + 1];
        }
        size--;
        selected = i - 1;
        return true;
    }

    /** Back to the straight line through (0,0) and (1,1); selects the first point. */
    public boolean reset() {
        boolean changed = size != 2 || xs[0] != 0 || ys[0] != 0 || xs[1] != 1 || ys[1] != 1;
        size = 2;
        xs[0] = 0;
        ys[0] = 0;
        xs[1] = 1;
        ys[1] = 1;
        selected = 0;
        return changed;
    }

    // ---- Hit testing ----

    /**
     * The point nearest to (x, y) within {@code radius} (Euclidean, in curve units; the plot
     * is square), or -1. On a tie the selected point wins, so it can always be grabbed again.
     */
    public int hitTest(double x, double y, double radius) {
        double r2 = radius * radius;
        int best = -1;
        double bestD = 0;
        for (int i = 0; i < size; i++) {
            double dx = xs[i] - x, dy = ys[i] - y;
            double d = dx * dx + dy * dy;
            if (d > r2) {
                continue;
            }
            if (best == -1 || d < bestD || (d == bestD && i == selected)) {
                best = i;
                bestD = d;
            }
        }
        return best;
    }

    /** The point whose x is closest to {@code x}. */
    public int nearestByX(double x) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            double d = Math.abs(xs[i] - x);
            if (d < bestD) {
                best = i;
                bestD = d;
            }
        }
        return best;
    }

    // ---- Helpers ----

    /** Value of the current (uncommitted) points' curve at x. */
    private double curveValue(double x) {
        return ToneCurve.of(points()).apply(x);
    }

    private double clampX(int i, double x) {
        if (i == 0) {
            return 0;
        }
        if (i == size - 1) {
            return 1;
        }
        double lo = minXAfter(xs[i - 1]);
        double hi = maxXBefore(xs[i + 1]);
        if (lo > hi) {
            // Cannot happen for valid points (the current x lies in [lo, hi]); stay put
            return xs[i];
        }
        return Math.max(lo, Math.min(hi, x));
    }

    /** Smallest x with {@code x - prev >= MIN_SPACING} in floating point. */
    static double minXAfter(double prev) {
        double lo = prev + ToneCurve.MIN_SPACING;
        while (lo - prev < ToneCurve.MIN_SPACING) {
            lo = Math.nextUp(lo);
        }
        return lo;
    }

    /** Largest x with {@code next - x >= MIN_SPACING} in floating point. */
    static double maxXBefore(double next) {
        double hi = next - ToneCurve.MIN_SPACING;
        while (next - hi < ToneCurve.MIN_SPACING) {
            hi = Math.nextDown(hi);
        }
        return hi;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
