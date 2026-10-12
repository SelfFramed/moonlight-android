package com.limelight.selfframed.grading;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class PresetBundleTest {
    private static final String[] NAMES = {"A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9"};

    private static PresetBundle defaults() {
        return new PresetBundle(BuiltinPresets.create(NAMES));
    }

    @Test
    public void jsonRoundTripKeepsEverything() throws Exception {
        PresetBundle a = defaults();
        GradingPreset p = a.slot(8);
        p.setName("Mine");
        p.setBrightness(120);
        p.setContrast(90);
        p.setSaturation(0);
        p.setGamma(1.85);
        p.setMono(true);
        p.setMonoWeights(0.5, 0.3, 0.2);
        p.setCurve(GradingPreset.CURVE_G, ToneCurve.of(new double[][] {{0, 0.1}, {0.123456789, 0.5}, {1, 0.9}}));
        a.setActiveSlot(8);
        a.setFilterEnabled(false);

        PresetBundle b = PresetBundle.fromJson(new JSONObject(a.toJson().toString()), BuiltinPresets.create(NAMES));
        assertEquals(8, b.getActiveSlot());
        assertFalse(b.isFilterEnabled());
        for (int s = 1; s <= PresetBundle.SLOT_COUNT; s++) {
            assertEquals("slot " + s, a.slot(s).toJson().toString(), b.slot(s).toJson().toString());
        }
        assertEquals(0.123456789, b.slot(8).getCurve(GradingPreset.CURVE_G).x(1), 0);
    }

    @Test
    public void importsUserscriptStateIntoTargetSlot() {
        // Saved state of game-curves.js / discord-stream-filter (localStorage)
        String userscript = "{\"brightness\":110,\"contrast\":105,\"saturation\":90,\"gamma\":1.25,"
                + "\"enabled\":true,\"movies\":false,\"curves\":{\"master\":[[0,0],[0.5,0.6],[1,1]],"
                + "\"r\":[[0,0],[1,1]],\"g\":[[0,0],[1,1]],\"b\":[[0,0],[1,0.95]]}}";
        PresetBundle b = defaults();
        assertEquals(1, b.importJson(userscript, 7));
        GradingPreset p = b.slot(7);
        assertEquals("A7", p.getName());
        assertEquals(110, p.getBrightness());
        assertEquals(105, p.getContrast());
        assertEquals(90, p.getSaturation());
        assertEquals(1.25, p.getGamma(), 0);
        assertEquals(0.6, p.getCurve(GradingPreset.CURVE_MASTER).y(1), 0);
        assertEquals(0.95, p.getCurve(GradingPreset.CURVE_B).y(1), 0);
        assertFalse(p.isMono());
    }

    @Test
    public void importsFullExport() {
        PresetBundle a = defaults();
        a.slot(2).setGamma(2.5);
        a.setActiveSlot(2);
        PresetBundle b = defaults();
        assertEquals(PresetBundle.SLOT_COUNT, b.importJson(a.toJson().toString(), 5));
        assertEquals(2.5, b.slot(2).getGamma(), 0);
        assertEquals(2, b.getActiveSlot());
    }

    @Test
    public void rejectsGarbage() {
        PresetBundle b = defaults();
        for (String bad : new String[] {"", "not json", "[]", "{\"foo\":1}"}) {
            try {
                b.importJson(bad, 1);
                fail("accepted: " + bad);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    @Test
    public void brokenSlotsFallBackToDefaults() throws Exception {
        JSONObject o = new JSONObject();
        JSONArray slots = new JSONArray();
        slots.put("garbage");
        slots.put(new JSONObject().put("name", "Two").put("gamma", 2.0));
        o.put("slots", slots);
        o.put("activeSlot", 42);
        PresetBundle b = PresetBundle.fromJson(o, BuiltinPresets.create(NAMES));
        assertEquals("A1", b.slot(1).getName());
        assertEquals(1.25, b.slot(1).getGamma(), 0);
        assertEquals("Two", b.slot(2).getName());
        assertEquals(2.0, b.slot(2).getGamma(), 0);
        assertEquals(PresetBundle.NEUTRAL_SLOT, b.getActiveSlot());
    }

    @Test
    public void outOfRangeValuesAreClamped() throws Exception {
        JSONObject o = new JSONObject()
                .put("brightness", 999).put("contrast", -5).put("saturation", 1000)
                .put("gamma", 9.0)
                .put("mono", new JSONObject().put("enabled", true).put("r", 4).put("g", -1).put("b", 0.5));
        GradingPreset p = GradingPreset.fromJson(o, "x");
        assertEquals(GradingPreset.BRIGHTNESS_MAX, p.getBrightness());
        assertEquals(GradingPreset.CONTRAST_MIN, p.getContrast());
        assertEquals(GradingPreset.SATURATION_MAX, p.getSaturation());
        assertEquals(GradingPreset.GAMMA_MAX, p.getGamma(), 0);
        assertEquals(1.0, p.getMonoR(), 0);
        assertEquals(0.0, p.getMonoG(), 0);
        assertEquals(0.5, p.getMonoB(), 0);
        assertEquals("x", p.getName());
    }

    @Test
    public void effectivePresetHonoursNeutralAndFilterSwitch() {
        PresetBundle b = defaults();
        assertNull(b.effectivePreset());
        b.setActiveSlot(3);
        assertSame(b.slot(3), b.effectivePreset());
        b.setFilterEnabled(false);
        assertNull(b.effectivePreset());
        b.setFilterEnabled(true);
        b.setActiveSlot(PresetBundle.NEUTRAL_SLOT);
        assertNull(b.effectivePreset());
    }

    @Test
    public void slotBoundsAreChecked() {
        PresetBundle b = defaults();
        for (int bad : new int[] {-1, 10}) {
            try {
                b.setActiveSlot(bad);
                fail("accepted slot " + bad);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
        try {
            b.slot(0);
            fail("slot 0 is neutral, not a preset");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        assertTrue(PresetBundle.isPresetSlot(9));
        assertFalse(PresetBundle.isPresetSlot(0));
    }
}
