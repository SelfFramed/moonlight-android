package com.limelight.selfframed.mic;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

/** Pairings with PC receivers, one per host (Moonlight's PC UUID): device id and audio key. */
final class MicPairings {
    private static final String PREFS = "sf_mic";
    private static final String KEY = "pairings_v2";

    static final class Entry {
        final int device;
        final byte[] key;

        Entry(int device, byte[] key) {
            this.device = device;
            this.key = key;
        }
    }

    private final SharedPreferences prefs;

    MicPairings(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private JSONObject all() {
        try {
            return new JSONObject(prefs.getString(KEY, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    Entry get(String host) {
        JSONObject e = all().optJSONObject(host);
        if (e == null) {
            return null;
        }
        try {
            return new Entry(e.getInt("device"), Base64.decode(e.getString("key"), Base64.NO_WRAP));
        } catch (JSONException | IllegalArgumentException ex) {
            return null;
        }
    }

    void put(String host, int device, byte[] key) {
        JSONObject all = all();
        try {
            all.put(host, new JSONObject().put("device", device).put("key", Base64.encodeToString(key, Base64.NO_WRAP)));
        } catch (JSONException e) {
            return;
        }
        prefs.edit().putString(KEY, all.toString()).apply();
    }

    void remove(String host) {
        JSONObject all = all();
        all.remove(host);
        prefs.edit().putString(KEY, all.toString()).apply();
    }

    int count() {
        return all().length();
    }

    void clear() {
        prefs.edit().remove(KEY).apply();
    }
}
