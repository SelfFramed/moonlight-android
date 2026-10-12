package com.limelight.selfframed.audio;

/**
 * Where the jitter buffer writes decoded audio: the AudioTrack on the device, a timing model in
 * the JVM tests. Frames are samples per channel.
 */
public interface PcmSink {
    /**
     * Writes the whole array (interleaved 16-bit PCM). Like {@code AudioTrack.write} in streaming
     * mode this blocks while the effective buffer is full, so it must not be called with more than
     * fits before {@link #play()}.
     */
    void write(short[] pcm);

    /** Frames written and not yet played. */
    int levelFrames();

    /** Underrun events since creation, or -1 if the platform cannot tell. */
    int underrunCount();

    /**
     * Sets the effective buffer size (writes beyond it block) and returns the size actually
     * applied, which may differ (rounding, or a fixed buffer on old Android versions).
     */
    int setBufferFrames(int frames);

    /** Starts playback of what has been written so far. */
    void play();
}
