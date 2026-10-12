package com.limelight.selfframed.grading;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * The Java port must behave exactly like curveFunction()/validateCurve() in Martin's
 * userscripts. The first tests compare against values produced by the original JavaScript;
 * the rest mirror the assertions of discord-stream-filter/curves.test.cjs.
 */
public class ToneCurveTest {
    private static final double EPS = 1e-12;

    @Test
    public void matchesOriginalJavaScriptSamples() throws Exception {
        JSONArray presets = ReferenceFixture.load().getJSONArray("presets");
        int checked = 0;
        for (int p = 0; p < presets.length(); p++) {
            JSONObject entry = presets.getJSONObject(p);
            JSONObject curves = entry.getJSONObject("preset").getJSONObject("curves");
            JSONObject samples = entry.getJSONObject("curveSamples");
            for (String key : GradingPreset.CURVE_KEYS) {
                ToneCurve curve = ToneCurve.of(ReferenceFixture.points(curves.get(key)));
                JSONArray expected = samples.getJSONArray(key);
                for (int i = 0; i < expected.length(); i++) {
                    double x = i / (double) (expected.length() - 1);
                    assertEquals(entry.getJSONObject("preset").getString("name") + "/" + key + " at " + x,
                            expected.getDouble(i), curve.apply(x), EPS);
                    checked++;
                }
            }
        }
        assertTrue(checked > 1000);
    }

    @Test
    public void validationMatchesOriginalJavaScript() throws Exception {
        JSONArray cases = ReferenceFixture.load().getJSONArray("validation");
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            double[][] input = ReferenceFixture.points(c.get("points"));
            double[][] expected = ReferenceFixture.points(c.get("result"));
            ToneCurve curve = ToneCurve.of(input);
            assertEquals("case " + i, expected.length, curve.size());
            for (int j = 0; j < expected.length; j++) {
                assertEquals(expected[j][0], curve.x(j), 0);
                assertEquals(expected[j][1], curve.y(j), 0);
            }
        }
    }

    @Test
    public void invalidInputYieldsIdentity() {
        assertSame(ToneCurve.IDENTITY, ToneCurve.of(null));
        assertSame(ToneCurve.IDENTITY, ToneCurve.of(new double[0][]));
        assertSame(ToneCurve.IDENTITY, ToneCurve.of(new double[][] {{0, 0}}));
        assertSame(ToneCurve.IDENTITY, ToneCurve.of(new double[][] {{0, Double.NaN}, {1, 1}}));
        assertSame(ToneCurve.IDENTITY, ToneCurve.of(new double[][] {{0, 0}, {0.5, 2}, {1, 1}}));
        assertSame(ToneCurve.IDENTITY, ToneCurve.of(new double[][] {{0, 0}, {0.01, 0.5}, {1, 1}}));
        assertSame(ToneCurve.IDENTITY, ToneCurve.of(new double[][] {{0.1, 0}, {1, 1}}));
        double[][] tooMany = new double[ToneCurve.MAX_POINTS + 1][];
        for (int i = 0; i < tooMany.length; i++) {
            tooMany[i] = new double[] {i / (double) (tooMany.length - 1), 0.5};
        }
        assertSame(ToneCurve.IDENTITY, ToneCurve.of(tooMany));
    }

    @Test
    public void validCurveIsCopiedNotShared() {
        double[][] valid = {{0, 0.1}, {0.25, 0.45}, {0.75, 0.6}, {1, 0.9}};
        ToneCurve curve = ToneCurve.of(valid);
        valid[1][1] = 0.99;
        assertEquals(0.45, curve.y(1), 0);
        double[][] copy = curve.points();
        copy[1][1] = 0.0;
        assertEquals(0.45, curve.y(1), 0);
    }

    @Test
    public void identityAndCollinearPoints() {
        for (double[][] pts : new double[][][] {{{0, 0}, {1, 1}}, {{0, 0}, {0.3, 0.3}, {1, 1}}}) {
            ToneCurve f = ToneCurve.of(pts);
            assertTrue(f.isIdentity());
            for (int i = 0; i <= 1000; i++) {
                assertEquals(i / 1000.0, f.apply(i / 1000.0), EPS);
            }
        }
    }

    @Test
    public void controlPointsAndClamping() {
        ToneCurve lift = ToneCurve.of(new double[][] {{0, 0}, {0.25, 0.45}, {1, 1}});
        assertFalse(lift.isIdentity());
        assertTrue(lift.apply(0.1) > 0.1);
        assertEquals(0.45, lift.apply(0.25), 0);
        assertEquals(0, lift.apply(-1), 0);
        assertEquals(1, lift.apply(2), 0);
    }

    @Test
    public void noOvershootBetweenControlPoints() {
        double[][][] cases = {
                {{0, 0.1}, {0.25, 0.45}, {0.75, 0.6}, {1, 0.9}},
                {{0, 1}, {0.2, 0}, {0.7, 0.9}, {1, 0.1}},
                {{0, 0}, {0.015, 0.9}, {0.98, 0.9}, {1, 1}},
        };
        for (double[][] pts : cases) {
            ToneCurve f = ToneCurve.of(pts);
            for (int j = 0; j < pts.length - 1; j++) {
                double x0 = pts[j][0], y0 = pts[j][1], x1 = pts[j + 1][0], y1 = pts[j + 1][1];
                for (int i = 0; i <= 1000; i++) {
                    double y = f.apply(x0 + (x1 - x0) * i / 1000.0);
                    assertTrue(y >= Math.min(y0, y1) - EPS && y <= Math.max(y0, y1) + EPS);
                }
            }
        }
    }

    @Test
    public void rgbComposition() {
        ToneCurve master = ToneCurve.of(new double[][] {{0, 0}, {0.5, 0.75}, {1, 1}});
        ToneCurve red = ToneCurve.of(new double[][] {{0, 0}, {1, 0.8}});
        assertEquals(0.6, red.apply(master.apply(0.5)), EPS);
    }
}
