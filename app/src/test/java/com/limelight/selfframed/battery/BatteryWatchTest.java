package com.limelight.selfframed.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class BatteryWatchTest {
    private static final BatteryWatch.Notice NONE = BatteryWatch.Notice.NONE;
    private static final BatteryWatch.Notice LOW = BatteryWatch.Notice.LOW;
    private static final BatteryWatch.Notice CHARGING = BatteryWatch.Notice.CHARGING;

    @Test
    public void dischargeWarnsOncePerBand() {
        BatteryWatch w = new BatteryWatch(15, 7);
        List<String> events = new ArrayList<>();
        for (int p = 100; p >= 0; p--) {
            boolean wasCritical = w.criticalVisible();
            BatteryWatch.Notice n = w.update(p, false);
            if (n != NONE) {
                events.add(n + "@" + p);
            }
            if (w.criticalVisible() && !wasCritical) {
                events.add("CRITICAL@" + p);
            }
        }
        assertEquals("[LOW@15, CRITICAL@7]", events.toString());
        assertTrue(w.criticalVisible());
    }

    @Test
    public void flickeringLevelDoesNotRepeat() {
        BatteryWatch w = new BatteryWatch(15, 7);
        assertEquals(NONE, w.update(16, false));
        assertEquals(LOW, w.update(15, false));
        assertEquals(NONE, w.update(16, false));
        assertEquals(NONE, w.update(15, false));
        assertEquals(NONE, w.update(14, false));
        w.update(7, false);
        w.update(8, false);
        assertTrue("critical stays while flickering", w.criticalVisible());
    }

    @Test
    public void streamStartingBelowAThresholdWarnsAtOnce() {
        assertEquals(LOW, new BatteryWatch(15, 7).update(12, false));
        BatteryWatch w = new BatteryWatch(15, 7);
        assertEquals("critical replaces the short notice", NONE, w.update(5, false));
        assertTrue(w.criticalVisible());
    }

    @Test
    public void chargingClearsConfirmsAndRearms() {
        BatteryWatch w = new BatteryWatch(15, 7);
        w.update(20, false);
        w.update(15, false);
        w.update(6, false);
        assertTrue(w.criticalVisible());
        assertEquals(CHARGING, w.update(6, true));
        assertFalse(w.criticalVisible());
        assertEquals(NONE, w.update(8, true));
        // Unplugged again below the first threshold: a new discharge, warned again
        assertEquals(LOW, w.update(9, false));
        assertEquals(NONE, w.update(8, false));
        w.update(7, false);
        assertTrue(w.criticalVisible());
    }

    @Test
    public void pluggedButNotChargingKeepsWarning() {
        // A lapdock that cannot keep up: plugged in, status "not charging", level falls
        BatteryWatch w = new BatteryWatch(15, 7);
        w.update(16, false);
        assertEquals(LOW, w.update(15, false));
        w.update(7, false);
        assertTrue(w.criticalVisible());
    }

    @Test
    public void snoozeLastsUntilTheNextPercentDrop() {
        BatteryWatch w = new BatteryWatch(15, 7);
        w.update(8, false);
        w.update(7, false);
        assertTrue(w.snooze());
        assertFalse(w.criticalVisible());
        w.update(7, false);
        assertFalse(w.criticalVisible());
        w.update(6, false);
        assertTrue(w.criticalVisible());
        assertFalse(new BatteryWatch(15, 7).snooze());
    }

    @Test
    public void quietWhenNoWarningWasDue() {
        BatteryWatch w = new BatteryWatch(15, 7);
        assertEquals("first update while charging", NONE, w.update(10, true));
        assertEquals("unplugged at a high level", NONE, w.update(60, false));
        assertEquals("plugged in without a warning before", NONE, w.update(59, true));
        assertFalse(w.criticalVisible());
    }

    @Test
    public void thresholdsAreSanitized() {
        BatteryWatch w = new BatteryWatch(10, 10);
        assertEquals(10, w.lowPercent());
        assertEquals(9, w.criticalPercent());
        BatteryWatch v = new BatteryWatch(150, -5);
        assertEquals(100, v.lowPercent());
        assertEquals(0, v.criticalPercent());
    }
}
