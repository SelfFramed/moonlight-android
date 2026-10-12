package com.limelight.selfframed.grading;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.view.Surface;

import com.limelight.LimeLog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GPU stage between the video decoder and the stream's SurfaceView:
 *
 *   MediaCodec -> Surface(SurfaceTexture, external OES texture) -> GLES2 shader -> EGL window
 *
 * The shader applies the {@link GradingLut} (grayscale mix, per-channel table, saturation).
 * A new LUT is shown immediately, even when the stream is static (the last frame is drawn
 * again), so preset switches never wait for the next video frame.
 *
 * Latency: the renderer draws as soon as the decoder releases a frame, drains to the newest
 * frame if it ever falls behind, and forwards the decoder's render timestamp to
 * SurfaceFlinger (eglPresentationTimeANDROID), so the frame pacing chosen in the settings
 * keeps its meaning.
 *
 * Threading: all GL/EGL work happens on one HandlerThread. Public methods are thread-safe.
 */
public final class GradingRenderer implements SurfaceTexture.OnFrameAvailableListener {
    private static final int INIT_TIMEOUT_MS = 3000;
    private static final int DETACH_TIMEOUT_MS = 2000;
    private static final int LUT_TEXEL_BYTES = GradingLut.SIZE * 4;

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTexCoord;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "varying highp vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = (uTexMatrix * aTexCoord).xy;\n" +
            "}\n";

    private static final String FRAGMENT_HEADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform samplerExternalOES uFrame;\n";

    private static final String FRAGMENT_PASSTHROUGH = FRAGMENT_HEADER +
            "void main() {\n" +
            "    gl_FragColor = vec4(texture2D(uFrame, vTexCoord).rgb, 1.0);\n" +
            "}\n";

    // Order: [grayscale mix] -> per-channel table (gamma, curves, brightness, contrast) -> saturation.
    // The table index maps 0..1 onto the texel centres of the 256-wide LUT, so linear filtering
    // interpolates between entries for values that are not on the 8-bit grid.
    private static final String FRAGMENT_GRADING = FRAGMENT_HEADER +
            "uniform sampler2D uLut;\n" +
            "uniform vec3 uMonoWeights;\n" +
            "uniform float uMono;\n" +
            "uniform float uSaturation;\n" +
            "const vec3 SAT_WEIGHTS = vec3(" + GradingLut.SAT_WEIGHT_R + ", " + GradingLut.SAT_WEIGHT_G + ", "
                    + GradingLut.SAT_WEIGHT_B + ");\n" +
            "const float LUT_SCALE = " + ((GradingLut.SIZE - 1) / (float) GradingLut.SIZE) + ";\n" +
            "const float LUT_OFFSET = " + (0.5f / GradingLut.SIZE) + ";\n" +
            "void main() {\n" +
            "    vec3 c = texture2D(uFrame, vTexCoord).rgb;\n" +
            "    c = mix(c, vec3(dot(c, uMonoWeights)), uMono);\n" +
            "    vec3 i = clamp(c, 0.0, 1.0) * LUT_SCALE + LUT_OFFSET;\n" +
            "    c = vec3(texture2D(uLut, vec2(i.r, 0.5)).r,\n" +
            "             texture2D(uLut, vec2(i.g, 0.5)).g,\n" +
            "             texture2D(uLut, vec2(i.b, 0.5)).b);\n" +
            "    c = mix(vec3(dot(c, SAT_WEIGHTS)), c, uSaturation);\n" +
            "    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);\n" +
            "}\n";

    // Full-screen triangle strip: x, y, u, v
    private static final float[] QUAD = {
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
    };

    /** Frame pacing modes that want SurfaceFlinger to drop superseded frames. */
    public static final int PACING_LOWEST_LATENCY = 0;
    /** Frames are released at vsync cadence by the decoder (balanced pacing). */
    public static final int PACING_VSYNC = 1;
    /** Every frame must be shown (cap FPS / max smoothness); never drop, never re-time. */
    public static final int PACING_NEVER_DROP = 2;

    private final HandlerThread thread;
    private final Handler handler;
    private final int pacing;
    private final AtomicBoolean renderScheduled = new AtomicBoolean();
    private final AtomicBoolean releaseRequested = new AtomicBoolean();
    private final AtomicReference<GradingLut> pendingLut = new AtomicReference<>(GradingLut.NEUTRAL);
    private final Runnable renderRunnable = new Runnable() {
        @Override
        public void run() {
            renderScheduled.set(false);
            render();
        }
    };

    // ---- GL thread state ----
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig eglConfig;
    private EGLSurface windowSurface = EGL14.EGL_NO_SURFACE;
    private EGLSurface pbufferSurface = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture surfaceTexture;
    private Surface decoderSurface;
    private int oesTexture, lutTexture;
    private Program passthrough, grading;
    private FloatBuffer quad;
    private ByteBuffer lutUpload;
    private GradingLut currentLut;
    private final float[] texMatrix = new float[16];
    private int framesAvailable;
    private boolean hasFrame;
    private long frameTimestampNs;
    private boolean released;
    private volatile Runnable firstFrameListener;

    // Statistics (GL thread writes, any thread reads a snapshot)
    private volatile String statsLine = "";
    private long statsWindowStartMs;
    private int statsFrames, statsDrained, statsRedraws;
    private long statsRenderNs;

    private static final class Program {
        int id, aPosition, aTexCoord, uTexMatrix, uFrame, uLut, uMonoWeights, uMono, uSaturation;
    }

    private GradingRenderer(int pacing) {
        this.pacing = pacing;
        thread = new HandlerThread("SF Grading GL", Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    /**
     * Creates the GPU stage for the given output surface and blocks until it is ready.
     *
     * @throws IllegalStateException if EGL/GLES setup fails; the caller should then let the
     *                               decoder render directly to the output surface
     */
    public static GradingRenderer create(final Surface output, final int videoWidth, final int videoHeight,
                                         int pacing) {
        final GradingRenderer r = new GradingRenderer(pacing);
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<RuntimeException> error = new AtomicReference<>();
        r.handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    r.initGl(output, videoWidth, videoHeight);
                } catch (RuntimeException e) {
                    error.set(e);
                    r.releaseGl();
                } finally {
                    done.countDown();
                }
            }
        });

        boolean finished;
        try {
            finished = done.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            r.release();
            throw new IllegalStateException("GL setup timed out");
        }
        if (error.get() != null) {
            r.thread.quitSafely();
            throw error.get();
        }
        return r;
    }

    /** The surface the video decoder must render into. */
    public Surface getInputSurface() {
        return decoderSurface;
    }

    /** Shows the given LUT from the next drawn frame on; redraws the current frame at once. */
    public void setLut(GradingLut lut) {
        pendingLut.set(lut != null ? lut : GradingLut.NEUTRAL);
        scheduleRender();
    }

    /**
     * Stops drawing to the output surface and destroys the EGL window surface. Must complete
     * before SurfaceHolder.Callback.surfaceDestroyed() returns. Decoded frames keep being
     * consumed afterwards so the decoder never stalls while the stream shuts down.
     */
    public void detachOutput() {
        runAndWait(new Runnable() {
            @Override
            public void run() {
                destroyWindowSurface();
            }
        }, DETACH_TIMEOUT_MS);
    }

    /** Releases everything asynchronously. Safe to call more than once, from any thread. */
    public void release() {
        if (releaseRequested.getAndSet(true)) {
            return;
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                releaseGl();
                thread.quitSafely();
            }
        });
    }

    /** Short status for the performance overlay (empty until the first frames were drawn). */
    public String getStatsLine() {
        return statsLine;
    }

    /** Runs once, on the GL thread, after the first video frame has been presented. */
    public void setFirstFrameListener(Runnable listener) {
        firstFrameListener = listener;
    }

    // ------------------------------------------------------------------------------------
    // GL thread
    // ------------------------------------------------------------------------------------

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        // Delivered on our handler thread
        framesAvailable++;
        scheduleRender();
    }

    private void scheduleRender() {
        if (!releaseRequested.get() && renderScheduled.compareAndSet(false, true)) {
            handler.post(renderRunnable);
        }
    }

    private void runAndWait(final Runnable task, int timeoutMs) {
        if (Thread.currentThread() == thread) {
            task.run();
            return;
        }
        final CountDownLatch done = new CountDownLatch(1);
        if (!handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    task.run();
                } finally {
                    done.countDown();
                }
            }
        })) {
            return; // Looper already quit
        }
        try {
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                LimeLog.warning("SelfFramed grading: GL thread did not respond in " + timeoutMs + " ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void initGl(Surface output, int videoWidth, int videoHeight) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new IllegalStateException("eglGetDisplay failed");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            throw eglFailure("eglInitialize");
        }

        int[] configAttribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] < 1) {
            throw eglFailure("eglChooseConfig");
        }
        eglConfig = configs[0];

        int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw eglFailure("eglCreateContext");
        }

        // Keeps the context current while no window is attached (after detachOutput())
        int[] pbufferAttribs = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
        pbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0);
        if (pbufferSurface == EGL14.EGL_NO_SURFACE) {
            throw eglFailure("eglCreatePbufferSurface");
        }

        windowSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, output, new int[] {EGL14.EGL_NONE}, 0);
        if (windowSurface == EGL14.EGL_NO_SURFACE) {
            throw eglFailure("eglCreateWindowSurface");
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext)) {
            throw eglFailure("eglMakeCurrent");
        }
        // Lowest latency: never block on the display queue, newer frames replace older ones.
        // Otherwise keep the default (blocking, nothing is dropped).
        EGL14.eglSwapInterval(eglDisplay, pacing == PACING_LOWEST_LATENCY ? 0 : 1);

        passthrough = buildProgram(FRAGMENT_PASSTHROUGH, false);
        grading = buildProgram(FRAGMENT_GRADING, true);

        quad = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(QUAD).position(0);
        lutUpload = ByteBuffer.allocateDirect(LUT_TEXEL_BYTES).order(ByteOrder.nativeOrder());

        int[] tex = new int[2];
        GLES20.glGenTextures(2, tex, 0);
        oesTexture = tex[0];
        lutTexture = tex[1];

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, GradingLut.SIZE, 1, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        uploadLut(pendingLut.get());
        checkGl("texture setup");

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);

        // Black first frame until the decoder delivers
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        EGL14.eglSwapBuffers(eglDisplay, windowSurface);

        surfaceTexture = new SurfaceTexture(oesTexture);
        surfaceTexture.setDefaultBufferSize(videoWidth, videoHeight);
        surfaceTexture.setOnFrameAvailableListener(this, handler);
        decoderSurface = new Surface(surfaceTexture);

        LimeLog.info("SelfFramed grading: GPU stage ready (EGL " + version[0] + "." + version[1]
                + ", " + GLES20.glGetString(GLES20.GL_RENDERER) + ", pacing " + pacing + ")");
    }

    private Program buildProgram(String fragmentSource, boolean withLut) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int id = GLES20.glCreateProgram();
        GLES20.glAttachShader(id, vs);
        GLES20.glAttachShader(id, fs);
        GLES20.glLinkProgram(id);
        int[] status = new int[1];
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0);
        // Shaders are no longer needed once linked (or if linking failed)
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        if (status[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetProgramInfoLog(id);
            GLES20.glDeleteProgram(id);
            throw new IllegalStateException("Program link failed: " + log);
        }

        Program p = new Program();
        p.id = id;
        p.aPosition = GLES20.glGetAttribLocation(id, "aPosition");
        p.aTexCoord = GLES20.glGetAttribLocation(id, "aTexCoord");
        p.uTexMatrix = GLES20.glGetUniformLocation(id, "uTexMatrix");
        p.uFrame = GLES20.glGetUniformLocation(id, "uFrame");
        if (withLut) {
            p.uLut = GLES20.glGetUniformLocation(id, "uLut");
            p.uMonoWeights = GLES20.glGetUniformLocation(id, "uMonoWeights");
            p.uMono = GLES20.glGetUniformLocation(id, "uMono");
            p.uSaturation = GLES20.glGetUniformLocation(id, "uSaturation");
        }
        GLES20.glUseProgram(id);
        GLES20.glUniform1i(p.uFrame, 0);
        if (withLut) {
            GLES20.glUniform1i(p.uLut, 1);
        }
        checkGl("program setup");
        return p;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("Shader compile failed: " + log);
        }
        return shader;
    }

    private void uploadLut(GradingLut lut) {
        lutUpload.position(0);
        lutUpload.put(lut.rgba());
        lutUpload.position(0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, GradingLut.SIZE, 1,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, lutUpload);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        currentLut = lut;
    }

    private void render() {
        if (released || surfaceTexture == null) {
            return;
        }
        long start = SystemClock.elapsedRealtimeNanos();

        boolean newFrame = framesAvailable > 0;
        if (newFrame) {
            // Latch every queued buffer; only the newest one is drawn. Keeps latency bounded
            // if this thread was ever delayed, and keeps the decoder's buffers flowing.
            int queued = framesAvailable;
            framesAvailable = 0;
            try {
                if (windowSurface == EGL14.EGL_NO_SURFACE) {
                    makeCurrent(pbufferSurface);
                }
                for (int i = 0; i < queued; i++) {
                    surfaceTexture.updateTexImage();
                }
            } catch (RuntimeException e) {
                LimeLog.warning("SelfFramed grading: updateTexImage failed: " + e);
                return;
            }
            surfaceTexture.getTransformMatrix(texMatrix);
            frameTimestampNs = surfaceTexture.getTimestamp();
            hasFrame = true;
            statsDrained += queued - 1;
        }

        GradingLut lut = pendingLut.get();
        boolean lutChanged = lut != currentLut;
        if (windowSurface == EGL14.EGL_NO_SURFACE || !hasFrame || (!newFrame && !lutChanged)) {
            return;
        }
        if (!makeCurrent(windowSurface)) {
            return;
        }
        if (lutChanged) {
            uploadLut(lut);
        }

        int[] size = new int[2];
        EGL14.eglQuerySurface(eglDisplay, windowSurface, EGL14.EGL_WIDTH, size, 0);
        EGL14.eglQuerySurface(eglDisplay, windowSurface, EGL14.EGL_HEIGHT, size, 1);
        GLES20.glViewport(0, 0, size[0], size[1]);

        Program p = lut.isNeutral() ? passthrough : grading;
        GLES20.glUseProgram(p.id);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
        if (p == grading) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexture);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glUniform3f(p.uMonoWeights, lut.monoR(), lut.monoG(), lut.monoB());
            GLES20.glUniform1f(p.uMono, lut.mono() ? 1f : 0f);
            GLES20.glUniform1f(p.uSaturation, lut.saturation());
        }
        GLES20.glUniformMatrix4fv(p.uTexMatrix, 1, false, texMatrix, 0);

        quad.position(0);
        GLES20.glVertexAttribPointer(p.aPosition, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(p.aPosition);
        quad.position(2);
        GLES20.glVertexAttribPointer(p.aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(p.aTexCoord);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // Forward the decoder's render time so SurfaceFlinger paces exactly as without us.
        // A redraw for a LUT change, or the never-drop modes, use the automatic timestamp.
        if (newFrame && pacing != PACING_NEVER_DROP && frameTimestampNs > 0) {
            EGLExt.eglPresentationTimeANDROID(eglDisplay, windowSurface, frameTimestampNs);
        }
        if (!EGL14.eglSwapBuffers(eglDisplay, windowSurface)) {
            int err = EGL14.eglGetError();
            LimeLog.warning("SelfFramed grading: eglSwapBuffers failed: 0x" + Integer.toHexString(err));
            if (err == EGL14.EGL_BAD_SURFACE || err == EGL14.EGL_BAD_NATIVE_WINDOW) {
                destroyWindowSurface();
            }
            return;
        }

        if (newFrame) {
            statsFrames++;
            Runnable listener = firstFrameListener;
            if (listener != null) {
                firstFrameListener = null;
                listener.run();
            }
        } else {
            statsRedraws++;
        }
        statsRenderNs += SystemClock.elapsedRealtimeNanos() - start;
        updateStats();
    }

    private void updateStats() {
        long now = SystemClock.elapsedRealtime();
        if (statsWindowStartMs == 0) {
            statsWindowStartMs = now;
            return;
        }
        long elapsed = now - statsWindowStartMs;
        if (elapsed < 1000) {
            return;
        }
        int drawn = statsFrames + statsRedraws;
        double avgMs = drawn > 0 ? statsRenderNs / 1e6 / drawn : 0;
        statsLine = String.format(Locale.ROOT, "%.2f ms/frame, %d fps, %d skipped",
                avgMs, Math.round(statsFrames * 1000.0 / elapsed), statsDrained);
        statsWindowStartMs = now;
        statsFrames = statsRedraws = statsDrained = 0;
        statsRenderNs = 0;
    }

    private boolean makeCurrent(EGLSurface surface) {
        if (!EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)) {
            LimeLog.warning("SelfFramed grading: eglMakeCurrent failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
            return false;
        }
        return true;
    }

    private void destroyWindowSurface() {
        if (windowSurface == EGL14.EGL_NO_SURFACE) {
            return;
        }
        if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
            makeCurrent(pbufferSurface);
        } else {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        }
        EGL14.eglDestroySurface(eglDisplay, windowSurface);
        windowSurface = EGL14.EGL_NO_SURFACE;
    }

    private void releaseGl() {
        if (released) {
            return;
        }
        released = true;

        if (surfaceTexture != null) {
            surfaceTexture.setOnFrameAvailableListener(null);
        }
        if (decoderSurface != null) {
            decoderSurface.release();
            decoderSurface = null;
        }
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            if (eglContext != EGL14.EGL_NO_CONTEXT && pbufferSurface != EGL14.EGL_NO_SURFACE
                    && EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)) {
                if (passthrough != null) {
                    GLES20.glDeleteProgram(passthrough.id);
                }
                if (grading != null) {
                    GLES20.glDeleteProgram(grading.id);
                }
                if (oesTexture != 0 || lutTexture != 0) {
                    GLES20.glDeleteTextures(2, new int[] {oesTexture, lutTexture}, 0);
                }
            }
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (windowSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, windowSurface);
                windowSurface = EGL14.EGL_NO_SURFACE;
            }
            if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, pbufferSurface);
                pbufferSurface = EGL14.EGL_NO_SURFACE;
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                eglContext = EGL14.EGL_NO_CONTEXT;
            }
            // No eglTerminate(): the default display is shared with the rest of the process
            EGL14.eglReleaseThread();
            eglDisplay = EGL14.EGL_NO_DISPLAY;
        }
        LimeLog.info("SelfFramed grading: GPU stage released");
    }

    private static IllegalStateException eglFailure(String what) {
        return new IllegalStateException(what + " failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
    }

    private static void checkGl(String what) {
        int err = GLES20.glGetError();
        if (err != GLES20.GL_NO_ERROR) {
            throw new IllegalStateException(what + ": GL error 0x" + Integer.toHexString(err));
        }
    }
}
