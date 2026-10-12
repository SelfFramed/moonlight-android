package com.limelight.selfframed.audio;

/** Crossfades for interleaved 16-bit PCM packets (pure Java, JVM-tested). */
public final class PcmBlend {
    private PcmBlend() {
    }

    /**
     * {@code out} starts as {@code from} and ends as {@code to}, with a linear crossfade over the
     * whole packet; all channels of a frame share one weight. Joining two packets this way keeps
     * both seams continuous: the start continues what came before {@code from}, the end leads
     * into what follows {@code to}. {@code out} may be one of the inputs.
     */
    public static void crossfade(short[] from, short[] to, short[] out, int channels) {
        int frames = out.length / channels;
        for (int f = 0; f < frames; f++) {
            double w = frames > 1 ? (double) f / (frames - 1) : 1.0;
            for (int c = 0; c < channels; c++) {
                int i = f * channels + c;
                out[i] = (short) Math.round(from[i] * (1.0 - w) + to[i] * w);
            }
        }
    }

    /** Linear fade-in over the first {@code fadeFrames} frames, against the click after a dropout. */
    public static void fadeIn(short[] pcm, int channels, int fadeFrames) {
        int frames = Math.min(fadeFrames, pcm.length / channels);
        for (int f = 0; f < frames; f++) {
            double w = (double) f / fadeFrames;
            for (int c = 0; c < channels; c++) {
                int i = f * channels + c;
                pcm[i] = (short) Math.round(pcm[i] * w);
            }
        }
    }
}
