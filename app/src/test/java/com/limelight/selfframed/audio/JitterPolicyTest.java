package com.limelight.selfframed.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class JitterPolicyTest {
    private static final long MS = 1_000_000L;
    private static final int PACKET = 240;

    private static JitterPolicy policy(int targetMs, boolean adaptive) {
        return new JitterPolicy(48000, PACKET, targetMs, 250, adaptive);
    }

    @Test
    public void targetIsWholePackets() {
        assertEquals(2400, policy(50, true).targetFrames());
        assertEquals(2640, policy(52, true).targetFrames());
        assertEquals(PACKET, policy(1, true).targetFrames());
    }

    @Test
    public void prefillAlwaysWrites() {
        JitterPolicy p = policy(60, true);
        assertEquals(JitterPolicy.Action.WRITE, p.onPacket(0, 100_000, 5, false, 0));
        assertEquals(0, p.dropouts());
    }

    @Test
    public void dropoutRaisesTargetOncePerSecond() {
        JitterPolicy p = policy(60, true);
        int level = p.targetFrames();
        p.onPacket(level, 0, 0, true, 0);                       // baseline of the counter
        p.onPacket(level, 0, 1, true, 1000 * MS);               // first dropout
        assertTrue(p.takeTargetChanged());
        assertEquals(80, p.targetMs());
        p.onPacket(level, 0, 2, true, 1500 * MS);               // same stall, within a second
        assertFalse(p.takeTargetChanged());
        assertEquals(80, p.targetMs());
        p.onPacket(level, 0, 3, true, 2600 * MS);
        assertEquals(100, p.targetMs());
        assertEquals(2, p.dropouts());
    }

    @Test
    public void targetStopsAtTheMaximum() {
        JitterPolicy p = new JitterPolicy(48000, PACKET, 240, 250, true);
        p.onPacket(0, 0, 0, true, 0);
        p.onPacket(0, 0, 1, true, 2000 * MS);
        p.onPacket(0, 0, 2, true, 4000 * MS);
        assertEquals(250, p.targetMs());
    }

    @Test
    public void fixedModeCountsButKeepsTarget() {
        JitterPolicy p = policy(100, false);
        p.onPacket(4800, 0, 0, true, 0);
        p.onPacket(4800, 0, 1, true, 1000 * MS);
        assertEquals(1, p.dropouts());
        assertEquals(100, p.targetMs());
        assertFalse(p.takeTargetChanged());
    }

    @Test
    public void unknownCounterUsesAnEmptyBuffer() {
        JitterPolicy p = policy(60, true);
        p.onPacket(2880, 0, -1, true, 0);
        assertEquals(0, p.dropouts());
        p.onPacket(0, 0, -1, true, 5 * MS);
        assertEquals(1, p.dropouts());
    }

    @Test
    public void backlogIsSkippedThenJoined() {
        JitterPolicy p = policy(60, true);
        int target = p.targetFrames();
        long t = 0;
        assertEquals(JitterPolicy.Action.SKIP, p.onPacket(target, target + PACKET, 0, true, t += 5 * MS));
        int pending = target;
        while (pending > target / 4) {
            assertEquals(JitterPolicy.Action.SKIP, p.onPacket(target, pending, 0, true, t += MS));
            pending -= PACKET;
        }
        assertEquals(JitterPolicy.Action.MERGE, p.onPacket(target, pending, 0, true, t + MS));
    }

    @Test
    public void driftCorrectionsAreRateLimited() {
        JitterPolicy p = policy(60, true);
        int low = p.targetFrames() / 4;
        int inserts = 0;
        for (long t = 0; t < 1000 * MS; t += 5 * MS) {
            if (p.onPacket(low, 0, 0, true, t) == JitterPolicy.Action.INSERT) {
                inserts++;
            }
        }
        // At most one correction per 250 ms
        assertTrue("inserts " + inserts, inserts >= 1 && inserts <= 4);
    }

    @Test
    public void steadyBufferNeedsNoCorrection() {
        JitterPolicy p = policy(60, true);
        int target = p.targetFrames();
        for (long t = 0; t < 10_000 * MS; t += 5 * MS) {
            // Level swings between target - 10 ms and target, as with 10 ms host bursts
            int level = target - (int) ((t / MS) % 10) * 48;
            assertEquals(JitterPolicy.Action.WRITE, p.onPacket(level, 0, 0, true, t));
        }
    }
}
