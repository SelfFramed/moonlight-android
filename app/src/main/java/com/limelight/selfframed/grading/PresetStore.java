package com.limelight.selfframed.grading;

import android.content.Context;
import android.content.SharedPreferences;

import com.limelight.LimeLog;
import com.limelight.R;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Persists the {@link PresetBundle} in its own SharedPreferences file ("sf_grading"), so the
 * fork never touches upstream preference keys.
 *
 * UI thread only. Every mutation is followed by {@link #save()} (asynchronous apply()), so
 * there is no explicit "save" step for the user.
 */
public final class PresetStore {
    private static final String PREFS_NAME = "sf_grading";
    private static final String KEY_BUNDLE = "bundle_v1";

    private final Context context;
    private final SharedPreferences prefs;
    private PresetBundle bundle;

    public PresetStore(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.bundle = load();
    }

    private PresetBundle load() {
        GradingPreset[] defaults = builtins();
        String json = prefs.getString(KEY_BUNDLE, null);
        if (json != null) {
            try {
                return PresetBundle.fromJson(new JSONObject(json), defaults);
            } catch (JSONException e) {
                LimeLog.warning("SelfFramed: stored grading presets unreadable, using defaults: " + e.getMessage());
            }
        }
        return new PresetBundle(defaults);
    }

    /** Factory defaults with localized names. */
    public GradingPreset[] builtins() {
        String[] names = context.getResources().getStringArray(R.array.sf_builtin_preset_names);
        return BuiltinPresets.create(names);
    }

    public PresetBundle bundle() {
        return bundle;
    }

    public void save() {
        prefs.edit().putString(KEY_BUNDLE, bundle.toJson().toString()).apply();
    }

    public void resetToBuiltins() {
        bundle = new PresetBundle(builtins());
        save();
    }

    /** Pretty-printed export of all slots, the active slot and the filter switch. */
    public String exportJson() {
        try {
            return bundle.toJson().toString(2);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Imports a full export or a single preset (see {@link PresetBundle#importJson}).
     *
     * @throws IllegalArgumentException with a user-readable message if the text is unusable
     */
    public int importJson(String text, int targetSlot) {
        int replaced = bundle.importJson(text, targetSlot);
        save();
        return replaced;
    }
}
