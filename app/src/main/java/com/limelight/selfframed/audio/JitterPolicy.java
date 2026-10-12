package com.limelight.selfframed.audio;

/**
 * Decides per audio packet how the buffer keeps a steady cushion against network and scheduling
 * jitter without letting the delay creep (pure Java, JVM-tested).
 *
 * <p>Model: the track's effective buffer equals the target and the decoder thread keeps it full
 * with blocking writes. Delay = frames in the track + frames still queued in moonlight-common-c.
 * <ul>
 * <li>A dropout (the track ran dry) raises the target in adaptive mode. Dropouts within one
 *     second count once, so a single network stall raises it by one step only.</li>
 * <li>Drift between the host's and the device's audio clocks moves the average delay slowly.
 *     Outside a dead band around the target, one packet is removed (MERGE) or added (INSERT),
 *     each joined with a crossfade, at most four times per second.</li>
 * <li>A large backlog after a long stall is skipped (SKIP) down to a quarter of the target and
 *     then joined with a crossfade (MERGE).</li>
 * </ul>
 */
public final class JitterPolicy {
    public enum Action { WRITE, MERGE, INSERT, SKIP }

    private static final long EMA_TAU_NS = 2_000_000_000L;
    private static final long MAX_EMA_STEP_NS = 100_000_000L;
    private static final long CORRECTION_GAP_NS = 250_000_000L;
    private static final long DROPOUT_DEBOUNCE_NS = 1_000_000_000L;
    private static final int STEP_MS = 20;
    private static final int MIN_TOLERANCE_MS = 20;
    private static final int MIN_SKIP_BACKLOG_MS = 40;

    private final int sampleRate;
    private final int packetFrames;
    private final int maxTargetFrames;
    private final boolean adaptive;

    private int targetFrames;
    private boolean targetChanged;

    private boolean started;
    private double ema;
    private long lastNs;
    private boolean corrected;
    private long lastCorrectionNs;
    private boolean droppedOut;
    private long lastDropoutNs;
    private int lastUnderrunCount = -1;
    private boolean skipping;

    private int dropouts;
    private int merges;
    private int inserts;
    private int skips;

    /**
     * @param packetFrames frames per decoded packet (240 for 5 ms at 48 kHz)
     * @param targetMs     starting target delay; rounded up to whole packets
     * @param maxTargetMs  upper limit for the adaptive target
     * @param adaptive     raise the target after dropouts
     */
    public JitterPolicy(int sampleRate, int packetFrames, int targetMs, int maxTargetMs, boolean adaptive) {
        this.sampleRate = sampleRate;
        this.packetFrames = packetFrames;
        this.adaptive = adaptive;
        this.targetFrames = roundUpToPackets(msToFrames(targetMs));
        this.maxTargetFrames = Math.max(targetFrames, roundUpToPackets(msToFrames(maxTargetMs)));
    }

    /**
     * @param levelFrames   frames in the track, measured before this packet is handled
     * @param pendingFrames frames queued before the decoder (moonlight-common-c)
     * @param underrunCount the track's underrun counter, or -1 if unknown
     * @param playing       whether playback has started (before that: prefill)
     * @param nowNs         monotonic time
     */
    public Action onPacket(int levelFrames, int pendingFrames, int underrunCount, boolean playing, long nowNs) {
        if (!playing) {
            return Action.WRITE;
        }

        boolean dropout;
        if (underrunCount >= 0) {
            dropout = lastUnderrunCount >= 0 && underrunCount > lastUnderrunCount;
            lastUnderrunCount = underrunCount;
        } else {
            dropout = levelFrames <= 0;
        }
        if (dropout && (!droppedOut || nowNs - lastDropoutNs >= DROPOUT_DEBOUNCE_NS)) {
            droppedOut = true;
            lastDropoutNs = nowNs;
            dropouts++;
            if (adaptive && targetFrames < maxTargetFrames) {
                targetFrames = Math.min(maxTargetFrames, targetFrames + roundUpToPackets(msToFrames(STEP_MS)));
                targetChanged = true;
            }
        }

        int delay = levelFrames + pendingFrames;
        if (!started || dropout) {
            started = true;
            ema = delay;
        } else {
            long dt = Math.min(Math.max(nowNs - lastNs, 0), MAX_EMA_STEP_NS);
            ema += (delay - ema) * ((double) dt / EMA_TAU_NS);
        }
        lastNs = nowNs;

        // Backlog after a long stall: catch up at once instead of carrying the extra delay
        if (skipping) {
            if (pendingFrames > targetFrames / 4) {
                skips++;
                return Action.SKIP;
            }
            skipping = false;
            noteCorrection(nowNs, levelFrames + pendingFrames);
            merges++;
            return Action.MERGE;
        }
        if (pendingFrames > Math.max(targetFrames, msToFrames(MIN_SKIP_BACKLOG_MS))) {
            skipping = true;
            skips++;
            return Action.SKIP;
        }

        // Clock drift: nudge the average delay back into the dead band, one packet at a time
        if (!corrected || nowNs - lastCorrectionNs >= CORRECTION_GAP_NS) {
            int tolerance = Math.max(msToFrames(MIN_TOLERANCE_MS), targetFrames / 4);
            if (ema > targetFrames + tolerance) {
                noteCorrection(nowNs, ema - packetFrames);
                merges++;
                return Action.MERGE;
            }
            if (ema < targetFrames - tolerance) {
                noteCorrection(nowNs, ema + packetFrames);
                inserts++;
                return Action.INSERT;
            }
        }
        return Action.WRITE;
    }

    private void noteCorrection(long nowNs, double newEma) {
        corrected = true;
        lastCorrectionNs = nowNs;
        // The average reacts slowly; account for the correction now so it is not repeated
        ema = newEma;
    }

    /** True once after the target changed; the renderer then resizes the track's buffer. */
    public boolean takeTargetChanged() {
        boolean changed = targetChanged;
        targetChanged = false;
        return changed;
    }

    public int targetFrames() {
        return targetFrames;
    }

    public int maxTargetFrames() {
        return maxTargetFrames;
    }

    public int targetMs() {
        return framesToMs(targetFrames);
    }

    public int packetFrames() {
        return packetFrames;
    }

    public int dropouts() {
        return dropouts;
    }

    public int merges() {
        return merges;
    }

    public int inserts() {
        return inserts;
    }

    public int skips() {
        return skips;
    }

    public int msToFrames(int ms) {
        return (int) ((long) ms * sampleRate / 1000);
    }

    public int framesToMs(int frames) {
        return (int) ((long) frames * 1000 / sampleRate);
    }

    private int roundUpToPackets(int frames) {
        int packets = Math.max(1, (frames + packetFrames - 1) / packetFrames);
        return packets * packetFrames;
    }
}
