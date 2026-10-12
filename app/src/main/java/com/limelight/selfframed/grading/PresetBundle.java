package com.limelight.selfframed.grading;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The complete grading state that is persisted: nine preset slots, the active slot
 * (0 = neutral) and the filter on/off switch (the before/after toggle).
 *
 * Pure Java (only org.json), so it can be unit-tested on the JVM. Persistence lives in
 * {@link PresetStore}.
 */
public final class PresetBundle {
    public static final int SLOT_COUNT = 9;
    public static final int NEUTRAL_SLOT = 0;

    public static final String FORMAT = "selfframed-moonlight-grading";
    public static final int VERSION = 1;

    private final GradingPreset[] slots = new GradingPreset[SLOT_COUNT];
    private int activeSlot = NEUTRAL_SLOT;
    private boolean filterEnabled = true;

    public PresetBundle(GradingPreset[] initialSlots) {
        if (initialSlots == null || initialSlots.length != SLOT_COUNT) {
            throw new IllegalArgumentException("Need " + SLOT_COUNT + " slots");
        }
        for (int i = 0; i < SLOT_COUNT; i++) {
            slots[i] = initialSlots[i] != null ? initialSlots[i] : GradingPreset.neutral("Preset " + (i + 1));
        }
    }

    public static boolean isPresetSlot(int slot) {
        return slot >= 1 && slot <= SLOT_COUNT;
    }

    /** Returns the live preset of slot 1..9. */
    public GradingPreset slot(int slot) {
        checkPresetSlot(slot);
        return slots[slot - 1];
    }

    public void setSlot(int slot, GradingPreset preset) {
        checkPresetSlot(slot);
        if (preset == null) {
            throw new IllegalArgumentException("preset == null");
        }
        slots[slot - 1] = preset;
    }

    public int getActiveSlot() {
        return activeSlot;
    }

    public void setActiveSlot(int slot) {
        if (slot != NEUTRAL_SLOT) {
            checkPresetSlot(slot);
        }
        activeSlot = slot;
    }

    public boolean isFilterEnabled() {
        return filterEnabled;
    }

    public void setFilterEnabled(boolean enabled) {
        filterEnabled = enabled;
    }

    /** The preset that should currently be rendered, or null for an untouched picture. */
    public GradingPreset effectivePreset() {
        if (!filterEnabled || activeSlot == NEUTRAL_SLOT) {
            return null;
        }
        return slots[activeSlot - 1];
    }

    // ---- JSON ----

    public JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("format", FORMAT);
            o.put("version", VERSION);
            o.put("activeSlot", activeSlot);
            o.put("filterEnabled", filterEnabled);
            JSONArray arr = new JSONArray();
            for (GradingPreset p : slots) {
                arr.put(p.toJson());
            }
            o.put("slots", arr);
            return o;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Restores a persisted bundle. Missing or broken slots fall back to the given defaults,
     * so a damaged store never takes the stream down.
     */
    public static PresetBundle fromJson(JSONObject o, GradingPreset[] defaults) {
        PresetBundle b = new PresetBundle(copyAll(defaults));
        if (o == null) {
            return b;
        }
        b.applyBundleJson(o);
        return b;
    }

    /**
     * Imports JSON text. Accepted:
     * - a full export of this app (format = FORMAT): replaces the slots it contains and
     *   takes over the active slot and the filter switch;
     * - a single preset, including the saved state of Martin's userscripts
     *   (game-curves.js / discord-stream-filter): goes into targetSlot.
     *
     * @return number of slots that were replaced
     * @throws IllegalArgumentException if the text is not one of the above
     */
    public int importJson(String text, int targetSlot) {
        JSONObject o;
        try {
            o = new JSONObject(text == null ? "" : text.trim());
        } catch (JSONException e) {
            throw new IllegalArgumentException("Kein gültiges JSON: " + e.getMessage(), e);
        }

        if (FORMAT.equals(o.optString("format")) || o.has("slots")) {
            return applyBundleJson(o);
        }

        if (looksLikePreset(o)) {
            checkPresetSlot(targetSlot);
            String keepName = slots[targetSlot - 1].getName();
            GradingPreset p = GradingPreset.fromJson(o, keepName);
            if (!o.has("name")) {
                p.setName(keepName);
            }
            slots[targetSlot - 1] = p;
            return 1;
        }

        throw new IllegalArgumentException("Unbekanntes Format: weder Preset-Export noch einzelnes Preset");
    }

    private int applyBundleJson(JSONObject o) {
        JSONArray arr = o.optJSONArray("slots");
        int replaced = 0;
        if (arr != null) {
            for (int i = 0; i < Math.min(arr.length(), SLOT_COUNT); i++) {
                JSONObject po = arr.optJSONObject(i);
                if (po != null) {
                    slots[i] = GradingPreset.fromJson(po, slots[i].getName());
                    replaced++;
                }
            }
        }
        int active = o.optInt("activeSlot", activeSlot);
        if (active == NEUTRAL_SLOT || isPresetSlot(active)) {
            activeSlot = active;
        }
        if (o.has("filterEnabled")) {
            filterEnabled = o.optBoolean("filterEnabled", true);
        }
        return replaced;
    }

    private static boolean looksLikePreset(JSONObject o) {
        return o.has("curves") || o.has("gamma") || o.has("brightness") || o.has("contrast")
                || o.has("saturation") || o.has("mono");
    }

    private static GradingPreset[] copyAll(GradingPreset[] src) {
        GradingPreset[] out = new GradingPreset[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) {
            out[i] = src != null && i < src.length && src[i] != null
                    ? src[i].copy() : GradingPreset.neutral("Preset " + (i + 1));
        }
        return out;
    }

    private static void checkPresetSlot(int slot) {
        if (!isPresetSlot(slot)) {
            throw new IllegalArgumentException("Slot must be 1.." + SLOT_COUNT + ": " + slot);
        }
    }
}
