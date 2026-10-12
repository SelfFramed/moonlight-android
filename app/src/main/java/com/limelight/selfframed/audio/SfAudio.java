package com.limelight.selfframed.audio;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.audio.AndroidAudioRenderer;
import com.limelight.nvstream.av.audio.AudioRenderer;

/**
 * Entry points for Game: the audio renderer chosen in the settings and its line in the
 * performance overlay (docs/selfframed/ARCHITEKTUR.md, section "Ton").
 */
public final class SfAudio {
    /** Setting: "auto", a fixed target in ms, or "upstream" for upstream's renderer. */
    public static final String PREF_BUFFER = "sf_audio_buffer";
    public static final String MODE_AUTO = "auto";
    public static final String MODE_UPSTREAM = "upstream";

    static final int MAX_TARGET_MS = 250;
    private static final int AUTO_DEFAULT_MS = 60;
    private static final int AUTO_MIN_MS = 40;
    /** After a stream without dropouts the next one starts this much lower. */
    private static final int AUTO_RELAX_MS = 10;
    private static final int FIXED_MIN_MS = 20;

    private static final String STATE_PREFS = "sf_audio";
    private static final String KEY_LEARNED_MS = "learned_target_ms";
    private static final String KEY_HAD_DROPOUTS = "last_stream_had_dropouts";

    private static volatile SfAudioRenderer current;
    private static volatile boolean upstreamInUse;

    private SfAudio() {
    }

    /** Game: the audio renderer for this stream. */
    public static AudioRenderer createRenderer(Context context, boolean enableAudioFx) {
        String mode = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_BUFFER, MODE_AUTO);
        if (MODE_UPSTREAM.equals(mode)) {
            upstreamInUse = true;
            LimeLog.info("SelfFramed audio: upstream renderer (setting)");
            return new AndroidAudioRenderer(context, enableAudioFx);
        }
        upstreamInUse = false;
        if (MODE_AUTO.equals(mode)) {
            SharedPreferences state = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
            int learned = state.getInt(KEY_LEARNED_MS, AUTO_DEFAULT_MS);
            // Start where the last stream ended; after a stream without dropouts a bit lower, so
            // the buffer can shrink again when the connection got better
            int start = state.getBoolean(KEY_HAD_DROPOUTS, true) ? learned : learned - AUTO_RELAX_MS;
            return new SfAudioRenderer(context, enableAudioFx, clamp(start, AUTO_MIN_MS, MAX_TARGET_MS), true);
        }
        int fixed;
        try {
            fixed = Integer.parseInt(mode);
        } catch (NumberFormatException e) {
            fixed = AUTO_DEFAULT_MS;
        }
        return new SfAudioRenderer(context, enableAudioFx, clamp(fixed, FIXED_MIN_MS, MAX_TARGET_MS), false);
    }

    /** Game, performance overlay: the audio line, or null. */
    public static String perfOverlayLine(Context context) {
        SfAudioRenderer r = current;
        if (r != null) {
            return r.statsLine(context);
        }
        return upstreamInUse ? context.getString(R.string.sf_audio_perf_upstream) : null;
    }

    static void setCurrent(SfAudioRenderer renderer) {
        current = renderer;
    }

    static void clearCurrent(SfAudioRenderer renderer) {
        if (current == renderer) {
            current = null;
        }
    }

    static void rememberSession(Context context, int finalTargetMs, int dropouts) {
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_LEARNED_MS, finalTargetMs)
                .putBoolean(KEY_HAD_DROPOUTS, dropouts > 0)
                .apply();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
