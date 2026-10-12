package com.limelight.selfframed.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class PcmBlendTest {
    private static final int CH = 2;
    private static final int FRAMES = 240;
    private static final double AMP = 20000;

    /** Packets of one continuous 1 kHz sine (left) and 440 Hz sine (right). */
    private static short[][] sine(int packets) {
        short[][] out = new short[packets][FRAMES * CH];
        for (int p = 0; p < packets; p++) {
            for (int f = 0; f < FRAMES; f++) {
                int n = p * FRAMES + f;
                out[p][f * CH] = (short) Math.round(AMP * Math.sin(2 * Math.PI * 1000 * n / 48000.0));
                out[p][f * CH + 1] = (short) Math.round(AMP * Math.sin(2 * Math.PI * 440 * n / 48000.0));
            }
        }
        return out;
    }

    /** Largest step between neighbouring frames of one channel over the joined packets. */
    private static double maxStep(List<short[]> packets, int channel) {
        double max = 0;
        Integer prev = null;
        for (short[] p : packets) {
            for (int f = 0; f < p.length / CH; f++) {
                int v = p[f * CH + channel];
                if (prev != null) {
                    max = Math.max(max, Math.abs(v - prev));
                }
                prev = v;
            }
        }
        return max;
    }

    @Test
    public void crossfadeRunsFromOneToTheOther() {
        short[] a = new short[FRAMES * CH];
        short[] b = new short[FRAMES * CH];
        java.util.Arrays.fill(a, (short) 1000);
        java.util.Arrays.fill(b, (short) -1000);
        short[] out = new short[FRAMES * CH];
        PcmBlend.crossfade(a, b, out, CH);
        assertEquals(1000, out[0]);
        assertEquals(1000, out[1]);
        assertEquals(-1000, out[out.length - 1]);
        for (int i = CH; i < out.length; i++) {
            assertTrue(out[i] <= out[i - CH]);
        }
    }

    @Test
    public void mergeKeepsBothSeamsSmooth() {
        short[][] s = sine(4);
        short[] merged = new short[FRAMES * CH];
        PcmBlend.crossfade(s[1], s[2], merged, CH);
        List<short[]> out = new ArrayList<>();
        out.add(s[0]);
        out.add(merged);
        out.add(s[3]);
        // A sine's own largest step is 13 % (1 kHz) of its amplitude; a hard cut could be 200 %
        assertTrue(maxStep(out, 0) <= 0.15 * AMP);
        assertTrue(maxStep(out, 1) <= 0.15 * AMP);
    }

    @Test
    public void insertKeepsBothSeamsSmooth() {
        short[][] s = sine(4);
        short[] extra = new short[FRAMES * CH];
        PcmBlend.crossfade(s[2], s[1], extra, CH);
        List<short[]> out = new ArrayList<>();
        out.add(s[0]);
        out.add(s[1]);
        out.add(extra);
        out.add(s[2]);
        out.add(s[3]);
        assertTrue(maxStep(out, 0) <= 0.15 * AMP);
        assertTrue(maxStep(out, 1) <= 0.15 * AMP);
    }

    @Test
    public void fadeInStartsSilentAndLeavesTheRest() {
        short[] p = new short[FRAMES * CH];
        java.util.Arrays.fill(p, (short) 8000);
        PcmBlend.fadeIn(p, CH, 120);
        assertEquals(0, p[0]);
        assertEquals(0, p[1]);
        assertEquals(4000, p[60 * CH]);
        assertEquals(8000, p[120 * CH]);
        assertEquals(8000, p[p.length - 1]);
    }
}
