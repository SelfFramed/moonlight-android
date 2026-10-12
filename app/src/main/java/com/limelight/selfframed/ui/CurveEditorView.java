package com.limelight.selfframed.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import com.limelight.selfframed.grading.ToneCurve;

/**
 * The tone-curve plot of the picture panel, drawn for e-ink: pure black on white, no fills
 * except the selected point, lines without anti-aliasing (no gray edge pixels for the panel to
 * dither), no animation.
 *
 * <ul>
 *   <li>square plot with a 2dp frame, dotted quarter grid and a dashed identity diagonal;</li>
 *   <li>the active channel's curve as a solid 3dp line;</li>
 *   <li>the other channels, if they are not the identity, as thin lines with their own dash
 *       pattern and a short boxed label (never told apart by colour);</li>
 *   <li>control points as squares: hollow, the selected one larger and filled black;</li>
 *   <li>axis labels 0 / 255.</li>
 * </ul>
 *
 * Mouse (and touch) editing:
 * <ul>
 *   <li>Press on a point: selects it; dragging moves it. The point keeps its offset to the
 *       pointer, inner points stay between their neighbours (MIN_SPACING), endpoints keep
 *       x = 0 / 1 and only move vertically.</li>
 *   <li>Press on an empty spot inside the plot: inserts a point at that x <em>on the current
 *       curve</em> (y = curve(x)), selects it and keeps dragging it. A plain click therefore
 *       adds an anchor without bending the curve (handy to pin a region before bending
 *       another one, and forgiving with an imprecise touchpad); press-and-drag shapes the
 *       curve in one gesture.</li>
 *   <li>Double-click on an inner point deletes it (endpoints cannot be deleted).</li>
 *   <li>Right and middle mouse buttons are ignored.</li>
 * </ul>
 * The view only edits the shared {@link CurvePointEditor} and reports through
 * {@link Listener}; the panel applies the result to the preset.
 *
 * Paths are rebuilt only when the data or the size changes; onDraw() does not allocate.
 */
public final class CurveEditorView extends View {
    /** Callbacks to the panel. */
    public interface Listener {
        /**
         * The pointer selected a point or started interacting with the plot. Always called
         * after {@link #onCurveEdited()} for the same press, so the listener may reload the
         * editor from the preset.
         */
        void onCurveTouched();

        /** The points of the edited curve changed (drag, insert, delete). */
        void onCurveEdited();
    }

    private static final int CHANNELS = 4;
    private static final int SAMPLES = 128;
    /** Where on the x axis the labels of the inactive channel curves sit, per channel. */
    private static final double[] LABEL_X = {0.40, 0.55, 0.70, 0.85};

    private final float density;
    private final CurvePointEditor editor;
    private final String[] shortLabels;

    private final Paint framePaint = new Paint();
    private final Paint gridPaint = new Paint();
    private final Paint identityPaint = new Paint();
    private final Paint activePaint = new Paint();
    private final Paint[] otherPaints = new Paint[CHANNELS];
    private final Paint pointFill = new Paint();
    private final Paint pointStroke = new Paint();
    private final Paint whiteFill = new Paint();
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelFrame = new Paint();

    private final Path gridPath = new Path();
    private final Path identityPath = new Path();
    private final Path activePath = new Path();
    private final Path[] otherPaths = new Path[CHANNELS];
    private final boolean[] otherVisible = new boolean[CHANNELS];
    private final RectF[] labelBoxes = new RectF[CHANNELS];
    private final float[] labelBaseline = new float[CHANNELS];

    private final RectF plot = new RectF();
    private final RectF tmp = new RectF();

    private final ToneCurve[] curves = new ToneCurve[CHANNELS];
    private int activeChannel;
    private int drawnSelected = -1;

    private final int marginLeft, marginRight, marginTop, marginBottom, minPlotSide;
    private final float pointSize, selectedSize, hitRadiusPx, axisTextHeight, axisAscent, axisGap;
    private final float labelClearance;
    private int maxPlotSide;

    private Listener listener;
    private int dragIndex = -1;
    private double grabDx, grabDy;
    private long lastDownTime;
    private int lastDownIndex = -1;

    public CurveEditorView(Context context, CurvePointEditor editor, String[] shortLabels) {
        super(context);
        this.editor = editor;
        this.shortLabels = shortLabels;
        this.density = context.getResources().getDisplayMetrics().density;

        setBackgroundColor(Color.WHITE);
        setFocusable(false);
        setSoundEffectsEnabled(false);
        setContentDescription(null);

        framePaint.setColor(Color.BLACK);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(dp(2));

        gridPaint.setColor(Color.BLACK);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(Math.max(1, Math.round(dp(1))));
        gridPaint.setPathEffect(new DashPathEffect(new float[] {dp(2), dp(5)}, 0));

        identityPaint.setColor(Color.BLACK);
        identityPaint.setStyle(Paint.Style.STROKE);
        identityPaint.setStrokeWidth(dp(1.5f));
        identityPaint.setPathEffect(new DashPathEffect(new float[] {dp(8), dp(6)}, 0));

        activePaint.setColor(Color.BLACK);
        activePaint.setStyle(Paint.Style.STROKE);
        activePaint.setStrokeWidth(dp(3));
        activePaint.setStrokeJoin(Paint.Join.ROUND);
        activePaint.setStrokeCap(Paint.Cap.ROUND);

        // Distinct dash patterns per channel: master long dash, R dash, G dots, B dash-dot
        float[][] dashes = {
                {dp(14), dp(6)},
                {dp(9), dp(5)},
                {dp(2.5f), dp(4)},
                {dp(10), dp(4), dp(2.5f), dp(4)},
        };
        for (int c = 0; c < CHANNELS; c++) {
            Paint p = new Paint();
            p.setColor(Color.BLACK);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setPathEffect(new DashPathEffect(dashes[c], 0));
            otherPaints[c] = p;
            otherPaths[c] = new Path();
            labelBoxes[c] = new RectF();
        }

        pointFill.setColor(Color.BLACK);
        pointFill.setStyle(Paint.Style.FILL);
        pointStroke.setColor(Color.BLACK);
        pointStroke.setStyle(Paint.Style.STROKE);
        pointStroke.setStrokeWidth(dp(2));
        whiteFill.setColor(Color.WHITE);
        whiteFill.setStyle(Paint.Style.FILL);

        float textPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18,
                context.getResources().getDisplayMetrics());
        axisPaint.setColor(Color.BLACK);
        axisPaint.setTextSize(textPx);
        axisPaint.setTypeface(Typeface.DEFAULT);
        labelPaint.setColor(Color.BLACK);
        labelPaint.setTextSize(textPx);
        labelPaint.setTypeface(Typeface.DEFAULT_BOLD);
        labelFrame.setColor(Color.BLACK);
        labelFrame.setStyle(Paint.Style.STROKE);
        labelFrame.setStrokeWidth(dp(1.5f));

        Paint.FontMetrics fm = axisPaint.getFontMetrics();
        axisTextHeight = fm.descent - fm.ascent;
        axisAscent = fm.ascent;
        axisGap = dp(4);
        pointSize = dp(12);
        selectedSize = dp(18);
        hitRadiusPx = dp(22);
        // Axis labels keep clear of the (large) selected point square on the plot's edges
        labelClearance = selectedSize / 2 + axisGap;
        marginLeft = Math.round(axisPaint.measureText("255") + labelClearance + axisGap);
        marginRight = Math.round(selectedSize / 2 + dp(2));
        marginTop = Math.round(selectedSize / 2 + dp(2));
        marginBottom = Math.round(labelClearance + axisTextHeight);
        minPlotSide = Math.round(dp(120));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Upper bound for the plot's side (the panel derives it from the screen height, so the
     * curve section fits on the first screen). Meant to be called from an ancestor's
     * onMeasure(): marks this view for re-measuring and returns true if the bound changed, so
     * the caller can mark the views in between, too (forceLayout(), no layout request).
     */
    public boolean setMaxPlotSide(int px) {
        if (px == maxPlotSide) {
            return false;
        }
        maxPlotSide = px;
        forceLayout();
        return true;
    }

    /**
     * New data from the panel: the four curves (master, r, g, b) and the active channel. The
     * points and the selection are read from the shared editor. Redraws only if something
     * visible changed.
     */
    public void setState(ToneCurve master, ToneCurve r, ToneCurve g, ToneCurve b, int channel) {
        boolean changed = channel != activeChannel || master != curves[0] || r != curves[1]
                || g != curves[2] || b != curves[3];
        curves[0] = master;
        curves[1] = r;
        curves[2] = g;
        curves[3] = b;
        activeChannel = channel;
        if (changed) {
            rebuildPaths();
            invalidate();
        } else if (drawnSelected != editor.selected()) {
            invalidate();
        }
    }

    /** Ends a drag in progress (panel hidden). */
    public void cancelDrag() {
        dragIndex = -1;
    }

    // ------------------------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------------------------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            width = Math.round(dp(320));
        }
        int side = width - marginLeft - marginRight;
        if (maxPlotSide > 0) {
            side = Math.min(side, maxPlotSide);
        }
        side = Math.max(side, minPlotSide);
        int height = side + marginTop + marginBottom;
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int availW = w - marginLeft - marginRight;
        int availH = h - marginTop - marginBottom;
        int side = Math.max(1, Math.min(availW, availH));
        float left = marginLeft + (availW - side) / 2f;
        float top = marginTop;
        plot.set(Math.round(left), Math.round(top), Math.round(left) + side, Math.round(top) + side);
        rebuildPaths();
    }

    private float px(double x) {
        return (float) (plot.left + x * plot.width());
    }

    private float py(double y) {
        return (float) (plot.bottom - y * plot.height());
    }

    private void rebuildPaths() {
        if (plot.width() <= 1) {
            return;
        }
        gridPath.reset();
        for (int i = 1; i < 4; i++) {
            float gx = px(i / 4.0), gy = py(i / 4.0);
            gridPath.moveTo(gx, plot.top);
            gridPath.lineTo(gx, plot.bottom);
            gridPath.moveTo(plot.left, gy);
            gridPath.lineTo(plot.right, gy);
        }
        identityPath.reset();
        identityPath.moveTo(plot.left, plot.bottom);
        identityPath.lineTo(plot.right, plot.top);

        ToneCurve active = curves[activeChannel] != null ? curves[activeChannel] : ToneCurve.IDENTITY;
        buildCurvePath(activePath, active);

        float pad = dp(4);
        Paint.FontMetrics fm = labelPaint.getFontMetrics();
        for (int c = 0; c < CHANNELS; c++) {
            ToneCurve curve = curves[c];
            otherVisible[c] = c != activeChannel && curve != null && !curve.isIdentity();
            if (!otherVisible[c]) {
                continue;
            }
            buildCurvePath(otherPaths[c], curve);
            // Boxed label just above the curve (below it near the top edge)
            double lx = LABEL_X[c];
            float cx = px(lx), cy = py(curve.apply(lx));
            float textW = labelPaint.measureText(shortLabels[c]);
            float boxW = textW + 2 * pad, boxH = (fm.descent - fm.ascent) + pad;
            float boxTop = cy - dp(6) - boxH;
            if (boxTop < plot.top + dp(2)) {
                boxTop = cy + dp(6);
            }
            float boxLeft = Math.max(plot.left + dp(2), Math.min(plot.right - dp(2) - boxW, cx - boxW / 2));
            labelBoxes[c].set(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH);
            labelBaseline[c] = boxTop + pad / 2 - fm.ascent;
        }
    }

    private void buildCurvePath(Path path, ToneCurve curve) {
        path.reset();
        path.moveTo(px(0), py(curve.apply(0)));
        for (int i = 1; i <= SAMPLES; i++) {
            double x = i / (double) SAMPLES;
            path.lineTo(px(x), py(curve.apply(x)));
        }
    }

    // ------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (plot.width() <= 1) {
            return;
        }
        canvas.drawPath(gridPath, gridPaint);
        canvas.drawPath(identityPath, identityPaint);

        for (int c = 0; c < CHANNELS; c++) {
            if (otherVisible[c]) {
                canvas.drawPath(otherPaths[c], otherPaints[c]);
            }
        }
        for (int c = 0; c < CHANNELS; c++) {
            if (otherVisible[c]) {
                RectF box = labelBoxes[c];
                canvas.drawRect(box, whiteFill);
                canvas.drawRect(box, labelFrame);
                canvas.drawText(shortLabels[c], box.left + dp(4), labelBaseline[c], labelPaint);
            }
        }

        canvas.drawPath(activePath, activePaint);
        canvas.drawRect(plot, framePaint);

        int selected = editor.selected();
        for (int i = 0; i < editor.size(); i++) {
            if (i == selected) {
                continue;
            }
            float cx = px(editor.x(i)), cy = py(editor.y(i));
            float h = pointSize / 2;
            tmp.set(cx - h, cy - h, cx + h, cy + h);
            canvas.drawRect(tmp, whiteFill);
            canvas.drawRect(tmp, pointStroke);
        }
        if (selected >= 0 && selected < editor.size()) {
            float cx = px(editor.x(selected)), cy = py(editor.y(selected));
            float h = selectedSize / 2;
            tmp.set(cx - h, cy - h, cx + h, cy + h);
            canvas.drawRect(tmp, pointFill);
        }
        drawnSelected = selected;

        // Axis labels: x below the plot, y left of it
        float xBase = plot.bottom + labelClearance - axisAscent;
        axisPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("0", plot.left, xBase, axisPaint);
        axisPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("255", plot.right, xBase, axisPaint);
        float yLabelX = plot.left - labelClearance;
        canvas.drawText("255", yLabelX, plot.top - axisAscent, axisPaint);
        canvas.drawText("0", yLabelX, plot.bottom, axisPaint);
    }

    // ------------------------------------------------------------------------------------
    // Mouse / touch
    // ------------------------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || plot.width() <= 1) {
            return false;
        }
        double ux = (event.getX() - plot.left) / plot.width();
        double uy = (plot.bottom - event.getY()) / plot.height();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (isOtherMouseButton(event)) {
                    return true;
                }
                onPress(ux, uy, event.getEventTime());
                return true;

            case MotionEvent.ACTION_MOVE:
                if (dragIndex >= 0 && dragIndex < editor.size()) {
                    if (editor.moveTo(dragIndex, ux + grabDx, uy + grabDy)) {
                        notifyEdited();
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (dragIndex >= 0) {
                    performClick();
                }
                dragIndex = -1;
                return true;

            case MotionEvent.ACTION_CANCEL:
                dragIndex = -1;
                return true;

            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void onPress(double ux, double uy, long time) {
        ViewParent parent = getParent();
        if (parent != null) {
            // Keep the panel's ScrollView from stealing the drag
            parent.requestDisallowInterceptTouchEvent(true);
        }
        double radius = hitRadiusPx / plot.width();
        int hit = editor.hitTest(ux, uy, radius);

        if (hit >= 0 && hit == lastDownIndex
                && time - lastDownTime <= ViewConfiguration.getDoubleTapTimeout()) {
            // Double-click: delete an inner point
            lastDownTime = 0;
            lastDownIndex = -1;
            dragIndex = -1;
            if (editor.delete(hit)) {
                notifyEdited();
            }
            notifyTouched();
            return;
        }

        boolean edited = false;
        if (hit >= 0) {
            editor.select(hit);
            dragIndex = hit;
        } else if (ux >= -radius && ux <= 1 + radius && uy >= -radius && uy <= 1 + radius) {
            int k = editor.insertOnCurve(clamp01(ux));
            if (k >= 0) {
                dragIndex = k;
                edited = true;
            } else {
                // No room for another point here: just select the nearest one
                editor.select(editor.nearestByX(ux));
                dragIndex = -1;
            }
        } else {
            dragIndex = -1;
        }
        if (dragIndex >= 0) {
            grabDx = editor.x(dragIndex) - ux;
            grabDy = editor.y(dragIndex) - uy;
        }
        lastDownTime = time;
        lastDownIndex = dragIndex;

        // Commit first: the panel reloads the editor from the preset when it is notified
        if (edited) {
            notifyEdited();
        }
        notifyTouched();
    }

    private static boolean isOtherMouseButton(MotionEvent event) {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            return false;
        }
        int buttons = event.getButtonState();
        return (buttons & (MotionEvent.BUTTON_SECONDARY | MotionEvent.BUTTON_TERTIARY)) != 0
                && (buttons & MotionEvent.BUTTON_PRIMARY) == 0;
    }

    private void notifyTouched() {
        if (listener != null) {
            listener.onCurveTouched();
        } else {
            invalidate();
        }
    }

    private void notifyEdited() {
        if (listener != null) {
            listener.onCurveEdited();
        } else {
            invalidate();
        }
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private float dp(float v) {
        return v * density;
    }
}
