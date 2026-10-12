package com.limelight.selfframed.grading;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class GradingLutTest {
    private static final String[] CHANNELS = {GradingPreset.CURVE_R, GradingPreset.CURVE_G, GradingPreset.CURVE_B};

    @Test
    public void tableMatchesOriginalJavaScriptPipeline() throws Exception {
        JSONArray presets = ReferenceFixture.load().getJSONArray("presets");
        for (int p = 0; p < presets.length(); p++) {
            JSONObject entry = presets.getJSONObject(p);
            JSONObject json = entry.getJSONObject("preset");
            GradingPreset preset = GradingPreset.fromJson(json, "x");
            // The fixture uses the exact userscript values; make sure parsing did not alter them
            assertEquals(json.getDouble("gamma"), preset.getGamma(), 0);
            assertEquals(json.getInt("brightness"), preset.getBrightness());
            assertEquals(json.getInt("contrast"), preset.getContrast());

            GradingLut lut = GradingLut.compile(preset);
            for (int c = 0; c < 3; c++) {
                JSONArray expected = entry.getJSONObject("lut").getJSONArray(CHANNELS[c]);
                for (int i = 0; i < GradingLut.SIZE; i++) {
                    double ref = expected.getDouble(i);
                    String where = json.getString("name") + "/" + CHANNELS[c] + "[" + i + "]";
                    assertEquals(where, ref, GradingLut.evaluate(preset, CHANNELS[c], i / 255.0), 1e-9);
                    assertEquals(where, (int) Math.round(ref * 255.0), lut.value(c, i));
                }
            }
        }
    }

    @Test
    public void neutralIsExactIdentity() {
        GradingLut lut = GradingLut.NEUTRAL;
        assertTrue(lut.isNeutral());
        assertFalse(lut.mono());
        assertEquals(1f, lut.saturation(), 0);
        for (int i = 0; i < GradingLut.SIZE; i++) {
            for (int c = 0; c < 3; c++) {
                assertEquals(i, lut.value(c, i));
            }
            assertEquals(255, lut.rgba()[i * 4 + 3] & 0xFF);
        }
    }

    @Test
    public void monoWeightsAreNormalized() {
        GradingPreset p = GradingPreset.neutral("m");
        p.setMono(true);
        p.setMonoWeights(1, 1, 0);
        GradingLut lut = GradingLut.compile(p);
        assertTrue(lut.mono());
        assertFalse(lut.isNeutral());
        assertEquals(0.5f, lut.monoR(), 1e-6);
        assertEquals(0.5f, lut.monoG(), 1e-6);
        assertEquals(0f, lut.monoB(), 1e-6);
    }

    @Test
    public void zeroMonoWeightsFallBackToGliderWeights() {
        GradingPreset p = GradingPreset.neutral("m");
        p.setMono(true);
        p.setMonoWeights(0, 0, 0);
        GradingLut lut = GradingLut.compile(p);
        assertEquals(0.299f, lut.monoR(), 1e-6);
        assertEquals(0.587f, lut.monoG(), 1e-6);
        assertEquals(0.114f, lut.monoB(), 1e-6);
    }

    @Test
    public void defaultMonoWeightsMatchGliderRgb2y() {
        // Glider rgb2y.v: y = (77 r + 150 g + 29 b) >> 8
        assertEquals(77 / 256.0, GradingPreset.MONO_DEFAULT_R, 0.002);
        assertEquals(150 / 256.0, GradingPreset.MONO_DEFAULT_G, 0.002);
        assertEquals(29 / 256.0, GradingPreset.MONO_DEFAULT_B, 0.002);
    }

    @Test
    public void saturationIsPassedAsFactor() {
        GradingPreset p = GradingPreset.neutral("s");
        p.setSaturation(150);
        GradingLut lut = GradingLut.compile(p);
        assertEquals(1.5f, lut.saturation(), 1e-6);
        assertFalse(lut.isNeutral());
        // Saturation is not baked into the per-channel table
        for (int i = 0; i < GradingLut.SIZE; i++) {
            assertEquals(i, lut.value(0, i));
        }
    }
}
