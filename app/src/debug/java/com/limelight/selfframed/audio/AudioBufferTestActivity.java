package com.limelight.selfframed.audio;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.TextView;

import com.limelight.nvstream.jni.MoonBridge;

/**
 * Debug builds only: plays a test tone through {@link SfAudioRenderer} the way a stream does
 * (two 5 ms packets every 10 ms) with an 80 ms network stall every 5 s, and logs the overlay line
 * once per second (logcat tag SfAudioTest). Volume via the extra "amp" (0 = silence).
 * adb shell am start -n com.selfframed.moonlight.debug/com.limelight.selfframed.audio.AudioBufferTestActivity --ei amp 0
 */
public class AudioBufferTestActivity extends Activity {
    private static final String TAG = "SfAudioTest";
    private static final int SECONDS = 30;
    private static final int STALL_EVERY_MS = 5000;
    private static final int STALL_MS = 80;

    private volatile boolean stop;
    private TextView text;
    private int amplitude;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        amplitude = getIntent().getIntExtra("amp", 1500);
        text = new TextView(this);
        text.setPadding(32, 32, 32, 32);
        text.setTextSize(18);
        text.setText("Audio buffer test …");
        setContentView(text);
        new Thread(this::run, "SfAudioTest").start();
    }

    @Override
    protected void onDestroy() {
        stop = true;
        super.onDestroy();
    }

    private void show(final String line) {
        Log.i(TAG, line);
        runOnUiThread(() -> text.setText(line));
    }

    private void run() {
        SfAudioRenderer r = new SfAudioRenderer(this, false, 60, true);
        if (r.setup(new MoonBridge.AudioConfiguration(2, 0x3), 48000, 240) != 0) {
            show("setup failed");
            return;
        }
        r.start();
        short[] pcm = new short[240 * 2];
        long sample = 0;
        long t0 = SystemClock.elapsedRealtime();
        long nextLog = 1000;
        for (int k = 0; !stop && k < SECONDS * 100; k++) {
            long due = k * 10L;
            long stallStart = due / STALL_EVERY_MS * STALL_EVERY_MS;
            if (stallStart > 0 && due < stallStart + STALL_MS) {
                // Held back by the stall, then delivered at once
                due = stallStart + STALL_MS;
            }
            long wait = t0 + due - SystemClock.elapsedRealtime();
            if (wait > 0) {
                SystemClock.sleep(wait);
            }
            for (int p = 0; p < 2; p++) {
                for (int f = 0; f < 240; f++, sample++) {
                    short v = (short) (amplitude * Math.sin(2 * Math.PI * 440 * sample / 48000.0));
                    pcm[2 * f] = v;
                    pcm[2 * f + 1] = v;
                }
                r.playDecodedAudio(pcm);
            }
            long elapsed = SystemClock.elapsedRealtime() - t0;
            if (elapsed >= nextLog) {
                show((elapsed / 1000) + " s: " + r.statsLine(this));
                nextLog += 1000;
            }
        }
        r.stop();
        r.cleanup();
        show("done: " + SECONDS + " s with an " + STALL_MS + " ms stall every " + STALL_EVERY_MS / 1000 + " s");
    }
}
