package com.limelight.selfframed.audio;

import java.util.ArrayDeque;
import java.util.Random;

/**
 * Timing model of the audio path in 0.25 ms steps: the host's audio engine delivers bursts
 * (measured on Martin's PC, 12.10.2026: 10 ms every 10 ms, Sunshine sends two 5 ms packets per
 * burst) → network delay and jitter → queue in moonlight-common-c → decoder thread (may stall) →
 * receiver under test → output buffer drained by the device's mixer at its own clock.
 */
final class AudioPipelineSim {
    static final int RATE = 48000;
    static final int CHANNELS = 2;
    static final int PACKET_MS = 5;
    static final int PACKET_FRAMES = RATE * PACKET_MS / 1000;
    private static final double STEP_MS = 0.25;

    interface Jitter {
        double extraMs(Random rnd);
    }

    interface Receiver {
        void onPacket(short[] pcm, int pendingFrames, long nowNs);
    }

    interface ReceiverFactory {
        Receiver create(SimSink sink);
    }

    double seconds = 120;
    double warmupSeconds = 10;
    /** The host's audio engine period: audio leaves the host in bursts of this length. */
    double hostPeriodMs = 10;
    /** The device mixer's period. */
    double consumerPeriodMs = 5;
    /** Normal (non-fast) mixer: a period plays only when the whole period is buffered. */
    boolean consumerNeedsFullPeriod;
    /** Positive: the device's audio clock runs faster than the host's. */
    double clockPpm;
    double baseDelayMs = 1;
    Jitter jitter = rnd -> rnd.nextDouble() * 2;
    /** Chance per packet that the decoder thread is not scheduled for a while afterwards. */
    double stallChancePerPacket;
    double stallMinMs;
    double stallMaxMs;
    long seed = 42;

    static final class Result {
        /** Underrun events of the output after the warm-up. */
        int underruns;
        double avgDelayMs;
        double maxDelayMs;

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "underruns %d, delay avg %.0f ms, max %.0f ms",
                    underruns, avgDelayMs, maxDelayMs);
        }
    }

    /** Output buffer with AudioTrack semantics: blocking writes, underrun events, effective size. */
    static final class SimSink implements PcmSink {
        private final int capacityFrames;
        private final boolean needsFullPeriod;
        int effective;
        int level;
        /** Frames of a write that is blocked because the buffer is full. */
        int outstanding;
        boolean playing;
        boolean justStarted;
        int underrunEvents;
        private boolean inUnderrun;

        SimSink(int capacityFrames, boolean needsFullPeriod) {
            this.capacityFrames = capacityFrames;
            this.needsFullPeriod = needsFullPeriod;
            this.effective = capacityFrames;
        }

        @Override
        public void write(short[] pcm) {
            int frames = pcm.length / CHANNELS;
            if (!playing && level + outstanding + frames > effective) {
                throw new IllegalStateException("write would block forever: playback not started");
            }
            outstanding += frames;
            drain();
        }

        void drain() {
            int n = Math.min(effective - level, outstanding);
            if (n > 0) {
                level += n;
                outstanding -= n;
            }
        }

        @Override
        public int levelFrames() {
            return level;
        }

        @Override
        public int underrunCount() {
            return underrunEvents;
        }

        @Override
        public int setBufferFrames(int frames) {
            effective = Math.min(frames, capacityFrames);
            drain();
            return effective;
        }

        @Override
        public void play() {
            if (!playing) {
                playing = true;
                justStarted = true;
            }
        }

        void consume(int need) {
            boolean short_;
            if (needsFullPeriod) {
                short_ = level < need;
                if (!short_) {
                    level -= need;
                }
            }
            else {
                int n = Math.min(level, need);
                level -= n;
                short_ = n < need;
            }
            if (short_) {
                // Consecutive short periods are one event, like AudioTrack.getUnderrunCount()
                if (!inUnderrun) {
                    underrunEvents++;
                }
                inUnderrun = true;
            }
            else {
                inUnderrun = false;
            }
            drain();
        }
    }

    /**
     * Model of upstream's AndroidAudioRenderer: plays at once from a small buffer (2 packets on a fast
     * mixer; Android enlarges it for normal mixers), drops packets while 40 ms are queued.
     */
    static final class UpstreamModel implements Receiver {
        private final SimSink sink;
        int dropped;

        UpstreamModel(SimSink sink, int bufferFrames) {
            this.sink = sink;
            sink.setBufferFrames(bufferFrames);
            sink.play();
        }

        @Override
        public void onPacket(short[] pcm, int pendingFrames, long nowNs) {
            if (pendingFrames * 1000 / RATE < 40) {
                sink.write(pcm);
            }
            else {
                dropped++;
            }
        }
    }

    Result run(ReceiverFactory factory) {
        // Separate streams, so every receiver sees the same network and the same stalls
        Random netRnd = new Random(seed);
        Random cpuRnd = new Random(seed * 31 + 7);
        SimSink sink = new SimSink(RATE, consumerNeedsFullPeriod);
        Receiver receiver = factory.create(sink);
        short[] pcm = new short[PACKET_FRAMES * CHANNELS];

        ArrayDeque<Double> inFlight = new ArrayDeque<>();
        int packetsPerBurst = (int) Math.round(hostPeriodMs / PACKET_MS);
        double nextBurst = 0;
        double lastArrival = 0;
        int queued = 0;
        double decoderFreeAt = 0;
        double consumerPeriodTrue = consumerPeriodMs / (1 + clockPpm * 1e-6);
        int need = (int) Math.round(consumerPeriodMs * RATE / 1000);
        double nextConsume = Double.MAX_VALUE;
        int underrunsAtWarmup = -1;
        double delaySum = 0;
        long delayCount = 0;
        double delayMax = 0;
        double end = seconds * 1000;
        double warm = warmupSeconds * 1000;

        for (long step = 0; step * STEP_MS < end; step++) {
            double t = step * STEP_MS;
            while (nextBurst <= t) {
                // UDP arrives in order here; a late burst holds back the ones behind it
                double arrival = Math.max(lastArrival, nextBurst + baseDelayMs + jitter.extraMs(netRnd));
                for (int i = 0; i < packetsPerBurst; i++) {
                    inFlight.add(arrival);
                }
                lastArrival = arrival;
                nextBurst += hostPeriodMs;
            }
            while (!inFlight.isEmpty() && inFlight.peekFirst() <= t) {
                inFlight.pollFirst();
                queued++;
            }

            if (sink.playing) {
                if (sink.justStarted) {
                    sink.justStarted = false;
                    nextConsume = t + consumerPeriodTrue;
                }
                while (nextConsume <= t) {
                    sink.consume(need);
                    nextConsume += consumerPeriodTrue;
                    if (t >= warm) {
                        if (underrunsAtWarmup < 0) {
                            underrunsAtWarmup = sink.underrunEvents;
                        }
                        double d = (sink.level + sink.outstanding + queued * PACKET_FRAMES) * 1000.0 / RATE;
                        delaySum += d;
                        delayCount++;
                        delayMax = Math.max(delayMax, d);
                    }
                }
            }

            sink.drain();
            while (queued > 0 && sink.outstanding == 0 && t >= decoderFreeAt) {
                queued--;
                receiver.onPacket(pcm, queued * PACKET_FRAMES, (long) (t * 1_000_000));
                if (stallChancePerPacket > 0 && cpuRnd.nextDouble() < stallChancePerPacket) {
                    decoderFreeAt = t + stallMinMs + cpuRnd.nextDouble() * (stallMaxMs - stallMinMs);
                }
            }
        }

        Result r = new Result();
        r.underruns = underrunsAtWarmup < 0 ? sink.underrunEvents : sink.underrunEvents - underrunsAtWarmup;
        r.avgDelayMs = delayCount > 0 ? delaySum / delayCount : 0;
        r.maxDelayMs = delayMax;
        return r;
    }
}
