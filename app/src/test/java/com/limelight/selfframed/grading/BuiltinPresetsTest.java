package com.limelight.selfframed.grading;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class BuiltinPresetsTest {
    private static final String[] NAMES = {"1", "2", "3", "4", "5", "6", "7", "8", "9"};

    /** Fixture names in slot order; the fixture must describe exactly what BuiltinPresets builds. */
    private static final String[] REFERENCE = {
            "film-game", "lift-shadows", "paper-white", "high-contrast", "threshold", "inverted"};

    @Test
    public void definedSlotsMatchTheReferenceFixture() throws Exception {
        GradingPreset[] slots = BuiltinPresets.create(NAMES);
        for (int i = 0; i < REFERENCE.length; i++) {
            JSONObject ref = ReferenceFixture.preset(REFERENCE[i]).getJSONObject("preset");
            GradingPreset expected = GradingPreset.fromJson(ref, NAMES[i]);
            expected.setName(NAMES[i]);
            assertEquals(REFERENCE[i], expected.toJson().toString(), slots[i].toJson().toString());
        }
    }

    @Test
    public void definedCurvesAreNotSilentlyIdentity() {
        // ToneCurve.of() falls back to identity on invalid points; a typo would go unnoticed
        GradingPreset[] slots = BuiltinPresets.create(NAMES);
        for (int i = 2; i < BuiltinPresets.DEFINED; i++) {
            assertFalse("slot " + (i + 1), slots[i].getCurve(GradingPreset.CURVE_MASTER).isIdentity());
        }
        for (int i = 0; i < BuiltinPresets.DEFINED; i++) {
            assertFalse("slot " + (i + 1), slots[i].isNeutral());
        }
        for (int i = BuiltinPresets.DEFINED; i < PresetBundle.SLOT_COUNT; i++) {
            assertTrue("slot " + (i + 1), slots[i].isNeutral());
        }
    }

    @Test
    public void invertedPresetInverts() {
        GradingLut lut = GradingLut.compile(BuiltinPresets.create(NAMES)[5]);
        for (int i = 0; i < GradingLut.SIZE; i++) {
            assertEquals(255 - i, lut.value(0, i));
        }
    }
}
