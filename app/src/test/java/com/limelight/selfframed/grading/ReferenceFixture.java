package com.limelight.selfframed.grading;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads src/test/resources/sf/curve-reference.json, generated from Martin's original
 * userscript by tools/sf/reference/make-curve-fixture.cjs.
 */
final class ReferenceFixture {
    private static JSONObject cached;

    private ReferenceFixture() {
    }

    static synchronized JSONObject load() throws Exception {
        if (cached == null) {
            try (InputStream in = ReferenceFixture.class.getResourceAsStream("/sf/curve-reference.json")) {
                if (in == null) {
                    throw new IOException("curve-reference.json missing; run tools/sf/reference/make-curve-fixture.cjs");
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                cached = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        }
        return cached;
    }

    static JSONObject preset(String name) throws Exception {
        JSONArray presets = load().getJSONArray("presets");
        for (int i = 0; i < presets.length(); i++) {
            JSONObject entry = presets.getJSONObject(i);
            if (entry.getJSONObject("preset").getString("name").equals(name)) {
                return entry;
            }
        }
        throw new IllegalArgumentException("No reference preset " + name);
    }

    /** Converts a JSON point list to double[][]; JSON null becomes Java null. */
    static double[][] points(Object json) throws Exception {
        if (json == null || json == JSONObject.NULL) {
            return null;
        }
        JSONArray arr = (JSONArray) json;
        double[][] pts = new double[arr.length()][];
        for (int i = 0; i < arr.length(); i++) {
            JSONArray p = arr.getJSONArray(i);
            pts[i] = new double[p.length()];
            for (int j = 0; j < p.length(); j++) {
                pts[i][j] = p.getDouble(j);
            }
        }
        return pts;
    }
}
