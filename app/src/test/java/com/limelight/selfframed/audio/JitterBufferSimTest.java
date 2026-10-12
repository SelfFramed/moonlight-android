package com.limelight.selfframed.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * End-to-end timing scenarios: the real {@link JitterBuffer} and {@link JitterPolicy} against
 * {@link AudioPipelineSim}, next to a model of upstream's renderer for comparison (printed).
 */
public class JitterBufferSimTest {
    private static final int AUTO_START_MS = 60;
    private static final int MAX_MS = 250;

    private static final class Ours implements AudioPipelineSim.ReceiverFactory {
        private final int targetMs;
        private final boolean adaptive;
        JitterBuffer buffer;

        Ours(int targetMs, boolean adaptive) {
            this.targetMs = targetMs;
            this.adaptive = adaptive;
        }

        @Override
        public AudioPipelineSim.Receiver create(AudioPipelineSim.SimSink sink) {
            final JitterBuffer b = new JitterBuffer(sink,
                    new JitterPolicy(AudioPipelineSim.RATE, AudioPipelineSim.PACKET_FRAMES, targetMs, MAX_MS, adaptive),
                    AudioPipelineSim.CHANNELS);
            buffer = b;
            return b::onPacket;
        }
    }

    private static final class Upstream implements AudioPipelineSim.ReceiverFactory {
        private final int bufferFrames;
        AudioPipelineSim.UpstreamModel model;

        Upstream(int bufferFrames) {
            this.bufferFrames = bufferFrames;
        }

        @Override
        public AudioPipelineSim.Receiver create(AudioPipelineSim.SimSink sink) {
            model = new AudioPipelineSim.UpstreamModel(sink, bufferFrames);
            return model;
        }
    }

    private static final class Outcome {
        AudioPipelineSim.Result ours;
        JitterBuffer buffer;
        AudioPipelineSim.Result upstream;
        int upstreamDropped;
    }

    private static Outcome compare(String scenario, AudioPipelineSim sim, int upstreamBufferFrames) {
        Ours ours = new Ours(AUTO_START_MS, true);
        Upstream upstream = new Upstream(upstreamBufferFrames);
        Outcome o = new Outcome();
        o.ours = sim.run(ours);
        o.buffer = ours.buffer;
        o.upstream = sim.run(upstream);
        o.upstreamDropped = upstream.model.dropped;
        System.out.printf(java.util.Locale.ROOT,
                "%-34s | SelfFramed: %s, target %d ms, -%d/+%d | upstream: %s, dropped %d%n",
                scenario, o.ours, o.buffer.targetMs(), o.buffer.shortened(), o.buffer.lengthened(),
                o.upstream, o.upstreamDropped);
        return o;
    }

    private static Outcome compare(String scenario, AudioPipelineSim sim) {
        return compare(scenario, sim, 2 * AudioPipelineSim.PACKET_FRAMES);
    }

    @Test
    public void measuredConditions() {
        // PC and Wi-Fi as measured on 12.10.2026: 10 ms bursts, RTT median 2.7 ms / p99 5.3 ms
        AudioPipelineSim sim = new AudioPipelineSim();
        Outcome o = compare("measured PC + Wi-Fi", sim);
        assertEquals(0, o.ours.underruns);
        assertEquals(0, o.buffer.shortened() + o.buffer.lengthened());
        assertTrue(o.ours.avgDelayMs > 45 && o.ours.avgDelayMs < 70);
    }

    @Test
    public void decoderThreadStalls() {
        // The phone does not schedule the decoder thread for 10-40 ms, about once per second
        AudioPipelineSim sim = new AudioPipelineSim();
        sim.stallChancePerPacket = 0.005;
        sim.stallMinMs = 10;
        sim.stallMaxMs = 40;
        Outcome o = compare("decoder stalls 10-40 ms ~1/s", sim);
        assertEquals(0, o.ours.underruns);
    }

    @Test
    public void wifiSpikesRaiseTheTarget() {
        // About once per second a 30-90 ms delay spike on top of normal jitter
        AudioPipelineSim sim = new AudioPipelineSim();
        sim.seconds = 300;
        sim.warmupSeconds = 60;
        sim.jitter = rnd -> rnd.nextDouble() * 2 + (rnd.nextDouble() < 0.01 ? 30 + rnd.nextDouble() * 60 : 0);
        Outcome o = compare("Wi-Fi spikes 30-90 ms ~1/s", sim);
        assertEquals(0, o.ours.underruns);
        assertTrue("target " + o.buffer.targetMs(), o.buffer.targetMs() >= 100);
        assertTrue(o.ours.underruns < o.upstream.underruns + o.upstreamDropped);
    }

    @Test
    public void deviceClockFaster() {
        AudioPipelineSim sim = new AudioPipelineSim();
        sim.seconds = 600;
        sim.clockPpm = 200;
        Outcome o = compare("device clock +200 ppm, 10 min", sim);
        assertEquals(0, o.ours.underruns);
        // 200 ppm over 600 s = 120 ms = 24 packets to add
        assertTrue("lengthened " + o.buffer.lengthened(), o.buffer.lengthened() >= 15 && o.buffer.lengthened() <= 40);
        assertTrue(Math.abs(o.ours.avgDelayMs - AUTO_START_MS) <= 25);
    }

    @Test
    public void deviceClockSlower() {
        AudioPipelineSim sim = new AudioPipelineSim();
        sim.seconds = 600;
        sim.clockPpm = -200;
        Outcome o = compare("device clock -200 ppm, 10 min", sim);
        assertEquals(0, o.ours.underruns);
        assertTrue("shortened " + o.buffer.shortened(), o.buffer.shortened() >= 15 && o.buffer.shortened() <= 40);
        assertTrue(o.ours.maxDelayMs <= AUTO_START_MS + 50);
    }

    @Test
    public void burstyOutputLikeBluetooth() {
        // A normal mixer that takes 20 ms at a time and only full periods (deep buffer, Bluetooth)
        AudioPipelineSim sim = new AudioPipelineSim();
        sim.consumerPeriodMs = 20;
        sim.consumerNeedsFullPeriod = true;
        // Android enlarges upstream's small buffer to about two mixer periods on such outputs
        Outcome o = compare("bursty 20 ms output", sim, 2 * 960);
        assertEquals(0, o.ours.underruns);
    }
}
