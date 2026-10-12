package com.limelight.selfframed.audio;

/**
 * The audio buffer between the Opus decoder and the output (pure Java, JVM-tested): holds one
 * packet back so corrections can be crossfaded, asks the {@link JitterPolicy} what to do with each
 * packet, and writes to the {@link PcmSink}. Playback starts once the target is buffered.
 *
 * <p>Runs on the audio decoder thread; the stats getters may be called from any thread.
 */
public final class JitterBuffer {
    /** Fade-in after the output ran dry: 2.5 ms at 48 kHz. */
    private static final int FADE_IN_FRAMES = 120;

    private final PcmSink sink;
    private final JitterPolicy policy;
    private final int channels;
    private final int packetShorts;
    private final short[] held;
    private final short[] scratch;
    private boolean hasHeld;
    private boolean playing;
    private int effectiveFrames;

    private volatile int statDelayMs;
    private volatile int statTargetMs;
    private volatile int statDropouts;
    private volatile int statShortened;
    private volatile int statLengthened;

    public JitterBuffer(PcmSink sink, JitterPolicy policy, int channels) {
        this.sink = sink;
        this.policy = policy;
        this.channels = channels;
        this.packetShorts = policy.packetFrames() * channels;
        this.held = new short[packetShorts];
        this.scratch = new short[packetShorts];
        this.effectiveFrames = sink.setBufferFrames(policy.targetFrames());
        this.statTargetMs = policy.targetMs();
    }

    /**
     * One decoded packet. The caller may reuse {@code pcm} afterwards.
     *
     * @param pendingFrames frames still queued before the decoder
     */
    public void onPacket(short[] pcm, int pendingFrames, long nowNs) {
        if (pcm.length != packetShorts) {
            // Not expected (the decoder output has a fixed size); pass it through in order
            if (hasHeld) {
                write(held);
                hasHeld = false;
            }
            write(pcm);
            return;
        }
        if (!hasHeld) {
            System.arraycopy(pcm, 0, held, 0, packetShorts);
            hasHeld = true;
            return;
        }

        int level = sink.levelFrames();
        JitterPolicy.Action action = policy.onPacket(level, pendingFrames, sink.underrunCount(), playing, nowNs);
        if (policy.takeTargetChanged()) {
            effectiveFrames = sink.setBufferFrames(policy.targetFrames());
        }
        boolean dry = playing && level <= 0;

        switch (action) {
            case SKIP:
                // Drop this packet; the held one is joined to a later packet with a crossfade
                break;
            case MERGE:
                // Two packets in, one out: shortens by one packet
                PcmBlend.crossfade(held, pcm, scratch, channels);
                if (dry) {
                    PcmBlend.fadeIn(scratch, channels, FADE_IN_FRAMES);
                }
                write(scratch);
                hasHeld = false;
                break;
            case INSERT:
                // One packet in, two out: lengthens by one packet
                if (dry) {
                    PcmBlend.fadeIn(held, channels, FADE_IN_FRAMES);
                }
                write(held);
                PcmBlend.crossfade(pcm, held, scratch, channels);
                write(scratch);
                System.arraycopy(pcm, 0, held, 0, packetShorts);
                break;
            default:
                if (dry) {
                    PcmBlend.fadeIn(held, channels, FADE_IN_FRAMES);
                }
                write(held);
                System.arraycopy(pcm, 0, held, 0, packetShorts);
                break;
        }

        statDelayMs = policy.framesToMs(level + pendingFrames);
        statTargetMs = policy.targetMs();
        statDropouts = policy.dropouts();
        statShortened = policy.merges() + policy.skips();
        statLengthened = policy.inserts();
    }

    private void write(short[] pcm) {
        int frames = pcm.length / channels;
        if (!playing && sink.levelFrames() + frames > effectiveFrames) {
            // Writing more would block before playback has started
            start();
        }
        sink.write(pcm);
        if (!playing && sink.levelFrames() >= Math.min(policy.targetFrames(), effectiveFrames)) {
            start();
        }
    }

    private void start() {
        sink.play();
        playing = true;
    }

    public boolean isPlaying() {
        return playing;
    }

    /** Frames in the output plus frames queued before the decoder, at the last packet. */
    public int delayMs() {
        return statDelayMs;
    }

    public int targetMs() {
        return statTargetMs;
    }

    public int dropouts() {
        return statDropouts;
    }

    /** Packets removed (drift corrections and skipped backlog). */
    public int shortened() {
        return statShortened;
    }

    /** Packets added (drift corrections). */
    public int lengthened() {
        return statLengthened;
    }
}
