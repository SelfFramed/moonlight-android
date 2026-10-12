package com.limelight.selfframed.grading;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;

/**
 * Test pattern through the real GPU picture stage, without a stream: gray steps that map onto
 * the panel's 16 levels, smooth ramps, text in several grays, a shadow ramp and colour pairs
 * of equal luma. Presets, hotkeys and the panel work exactly as in the stream, so presets can
 * be tuned on the e-ink panel at leisure. Also the end-to-end check for emulators and CI-less
 * device tests (docs/selfframed/TESTPROTOKOLL.md).
 *
 * Keys: the stream hotkeys (Ctrl+Alt+Shift + 1-9, 0, F, P, arrows) and, since there is no host
 * here, the same keys without modifiers. Escape closes the panel, then the screen.
 */
public class GradingTestPatternActivity extends Activity implements SurfaceHolder.Callback {
    private SurfaceView surfaceView;
    private GradingController controller;
    private boolean attached;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surfaceView = new SurfaceView(this);
        surfaceView.setFocusable(true);
        surfaceView.setFocusableInTouchMode(true);
        root.addView(surfaceView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        surfaceView.requestFocus();

        controller = GradingController.createForPreview(this, root, surfaceView,
                new GradingController.StreamHost() {
                    @Override
                    public boolean isInputGrabbed() {
                        return false;
                    }

                    @Override
                    public void setInputGrabbed(boolean grabbed) {
                        // Nothing is captured on this screen
                    }
                });
        surfaceView.getHolder().addCallback(this);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (attached) {
            return;
        }
        attached = true;
        Surface input = controller.attachOutput(holder, width, height,
                PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS);
        if (input == null) {
            // Stage unavailable: show the pattern unprocessed so the screen is not black
            input = holder.getSurface();
        }
        drawPattern(input, width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        controller.detachOutput();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        controller.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return handleKey(event) || super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return handleKey(event) || super.onKeyUp(keyCode, event);
    }

    private boolean handleKey(KeyEvent event) {
        if (controller.handleKeyEvent(event)) {
            return true;
        }
        int code = event.getKeyCode();
        if (KeyEvent.isModifierKey(code) || !controller.isHotkey(code)) {
            return false;
        }
        // With or without Ctrl+Alt+Shift: there is no host to collide with here
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            controller.onHotkey(code);
        }
        return true;
    }

    private static void drawPattern(Surface surface, int w, int h) {
        Canvas c;
        try {
            c = surface.lockCanvas(null);
        } catch (RuntimeException e) {
            LimeLog.warning("SelfFramed test pattern: lockCanvas failed: " + e);
            return;
        }
        try {
            paintPattern(c, w, h);
        } finally {
            surface.unlockCanvasAndPost(c);
        }
    }

    /** Layout in fractions of the surface so it fits 1600x1200 as well as phone screens. */
    static void paintPattern(Canvas c, int w, int h) {
        Paint fill = new Paint();
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTypeface(Typeface.SANS_SERIF);
        c.drawColor(Color.WHITE);

        int margin = Math.round(w * 0.02f);
        int innerW = w - 2 * margin;
        float label = h * 0.022f;
        int y = margin;

        // 1. 16 gray steps (the panel shows 16 levels)
        int stepsH = Math.round(h * 0.13f);
        text.setTextSize(label);
        for (int i = 0; i < 16; i++) {
            int v = Math.round(i * 255f / 15f);
            fill.setColor(Color.rgb(v, v, v));
            int x0 = margin + innerW * i / 16, x1 = margin + innerW * (i + 1) / 16;
            c.drawRect(x0, y, x1, y + stepsH, fill);
            text.setColor(v < 128 ? Color.WHITE : Color.BLACK);
            c.drawText(Integer.toString(i), x0 + label * 0.4f, y + stepsH - label * 0.5f, text);
        }
        y += stepsH + margin / 2;

        // 2. Smooth ramp 0..255 and a shadow ramp 0..64
        int rampH = Math.round(h * 0.07f);
        fill.setShader(new LinearGradient(margin, 0, margin + innerW, 0, Color.BLACK, Color.WHITE, Shader.TileMode.CLAMP));
        c.drawRect(margin, y, margin + innerW, y + rampH, fill);
        y += rampH + margin / 3;
        fill.setShader(new LinearGradient(margin, 0, margin + innerW, 0, Color.BLACK, Color.rgb(64, 64, 64),
                Shader.TileMode.CLAMP));
        c.drawRect(margin, y, margin + innerW, y + rampH, fill);
        fill.setShader(null);
        text.setColor(Color.rgb(200, 200, 200));
        c.drawText("0 … 64 (Schatten)", margin + label * 0.5f, y + rampH - label * 0.5f, text);
        y += rampH + margin / 2;

        // 3. Text in several grays, on white and on dark
        int textBlockH = Math.round(h * 0.30f);
        int half = innerW / 2;
        int[] grays = {0, 64, 128, 170, 200};
        float lineH = textBlockH / (grays.length + 0.5f);
        for (int i = 0; i < grays.length; i++) {
            int g = grays[i];
            text.setColor(Color.rgb(g, g, g));
            text.setTextSize(lineH * 0.42f);
            c.drawText("Grau " + g + ": Gradation Hamburgefonstiv 0123", margin, y + lineH * (i + 0.8f), text);
        }
        fill.setColor(Color.rgb(40, 40, 40));
        c.drawRect(margin + half, y, margin + innerW, y + textBlockH, fill);
        int[] lights = {255, 200, 140, 90, 60};
        for (int i = 0; i < lights.length; i++) {
            int g = lights[i];
            text.setColor(Color.rgb(g, g, g));
            text.setTextSize(lineH * 0.42f);
            c.drawText("Hell " + g + " auf Dunkel 40", margin + half + label, y + lineH * (i + 0.8f), text);
        }
        y += textBlockH + margin / 2;

        // 4. Small text sizes
        text.setColor(Color.BLACK);
        float size = h * 0.012f;
        float x = margin;
        for (int i = 0; i < 6; i++) {
            text.setTextSize(size);
            String s = Math.round(size) + "px Text ";
            c.drawText(s, x, y + h * 0.035f, text);
            x += text.measureText(s) + label;
            size *= 1.25f;
        }
        y += Math.round(h * 0.05f);

        // 5. Colour pairs with (nearly) equal BT.601 luma: indistinguishable on the panel unless
        // the grayscale mix separates them
        int[][] pairs = {
                {Color.rgb(150, 75, 0), Color.rgb(40, 80, 200)},     // brown vs. blue
                {Color.rgb(200, 0, 0), Color.rgb(0, 102, 0)},        // red vs. green
                {Color.rgb(255, 200, 0), Color.rgb(140, 220, 255)},  // yellow vs. light blue
                {Color.RED, Color.GREEN, Color.BLUE, Color.CYAN, Color.MAGENTA, Color.YELLOW},
        };
        int rows = pairs.length;
        int boxH = Math.max(1, (h - margin - y) / rows - margin / 3);
        for (int r = 0; r < rows; r++) {
            int n = pairs[r].length;
            for (int i = 0; i < n; i++) {
                fill.setColor(pairs[r][i]);
                Rect box = new Rect(margin + innerW * i / n, y, margin + innerW * (i + 1) / n - margin / 3, y + boxH);
                c.drawRect(box, fill);
            }
            y += boxH + margin / 3;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }
}
