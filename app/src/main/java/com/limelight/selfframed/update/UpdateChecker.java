package com.limelight.selfframed.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;
import android.widget.Toast;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.R;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Looks for a newer release of this fork on GitHub and offers to install it. No extra app is
 * needed: the APK is downloaded and handed to Android's package installer, which asks the
 * user to confirm and only accepts it if it is signed with the same key as the installed app.
 *
 * Runs on the main screen (never during a stream): automatically at most every 6 hours, or
 * on demand from the settings. Release builds only; debug builds have a different app ID.
 */
public final class UpdateChecker {
    /** Settings switch, default on. */
    public static final String PREF_AUTO = "sf_update_auto";

    private static final String PREFS = "sf_update";
    private static final String KEY_LAST_CHECK = "last_check_ms";
    private static final String KEY_SKIPPED = "skipped_version";
    private static final long AUTO_INTERVAL_MS = 6L * 60 * 60 * 1000;
    private static final int TIMEOUT_MS = 15000;
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;

    private static volatile boolean checking;

    private UpdateChecker() {
    }

    /** Asset name ending for this build, e.g. "-nonRoot-release.apk". */
    static String apkSuffix() {
        return "-" + BuildConfig.FLAVOR + "-release.apk";
    }

    /** PcView.onResume(): resume a pending install, or run the throttled automatic check. */
    public static void onMainScreenResumed(Activity activity) {
        if (BuildConfig.DEBUG) {
            return;
        }
        if (UpdateInstaller.resumePendingInstall(activity)) {
            return;
        }
        if (!PreferenceManager.getDefaultSharedPreferences(activity).getBoolean(PREF_AUTO, true)) {
            return;
        }
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long last = prefs.getLong(KEY_LAST_CHECK, 0);
        if (now - last >= 0 && now - last < AUTO_INTERVAL_MS) {
            return;
        }
        check(activity, false);
    }

    /** Settings button: check now and always report the result. */
    public static void checkNow(Activity activity) {
        if (BuildConfig.DEBUG) {
            toast(activity, activity.getString(R.string.sf_update_debug_build));
            return;
        }
        toast(activity, activity.getString(R.string.sf_update_checking));
        check(activity, true);
    }

    private static void check(final Activity activity, final boolean manual) {
        if (checking) {
            return;
        }
        checking = true;
        final Context app = activity.getApplicationContext();
        new Thread("SF update check") {
            @Override
            public void run() {
                ReleaseInfo release = null;
                String error = null;
                try {
                    release = fetchLatest();
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
                } catch (IOException | RuntimeException e) {
                    error = e.getMessage();
                    LimeLog.warning("SelfFramed update check failed: " + e);
                } finally {
                    checking = false;
                }
                final ReleaseInfo result = release;
                final String failure = error;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!UpdateChecker.isAlive(activity)) {
                            return;
                        }
                        onResult(activity, result, failure, manual);
                    }
                });
            }
        }.start();
    }

    /** @return the newest published release, or null if there is none (404) */
    static ReleaseInfo fetchLatest() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(BuildConfig.SF_UPDATE_API).openConnection();
        try {
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setRequestProperty("User-Agent", "Moonlight-E-Ink/" + BuildConfig.VERSION_NAME);
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                return null;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    body.write(buf, 0, n);
                    if (body.size() > MAX_RESPONSE_BYTES) {
                        throw new IOException("response too large");
                    }
                }
                JSONObject json = new JSONObject(new String(body.toByteArray(), StandardCharsets.UTF_8));
                return ReleaseInfo.parse(json, apkSuffix());
            } catch (org.json.JSONException e) {
                throw new IOException("unreadable release data", e);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void onResult(final Activity activity, final ReleaseInfo release, String error, boolean manual) {
        if (error != null) {
            if (manual) {
                toast(activity, activity.getString(R.string.sf_update_error, error));
            }
            return;
        }
        String installed = BuildConfig.VERSION_NAME;
        if (release == null || !release.isNewerThan(installed)) {
            if (manual) {
                toast(activity, activity.getString(R.string.sf_update_none, installed));
            }
            return;
        }
        final SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!manual && release.versionName.equals(prefs.getString(KEY_SKIPPED, null))) {
            return;
        }

        String message = activity.getString(R.string.sf_update_message, release.versionName, installed);
        String notes = release.notes.trim();
        if (!notes.isEmpty()) {
            message += "\n\n" + (notes.length() > 600 ? notes.substring(0, 600) + " …" : notes);
        }
        dialogBuilder(activity)
                .setTitle(R.string.sf_update_title)
                .setMessage(message)
                .setPositiveButton(R.string.sf_update_install, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        UpdateInstaller.start(activity, release);
                    }
                })
                .setNegativeButton(R.string.sf_update_later, null)
                .setNeutralButton(R.string.sf_update_skip, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.edit().putString(KEY_SKIPPED, release.versionName).apply();
                    }
                })
                .show();
    }

    /** Light dialogs: black on white reads best on e-ink. */
    static AlertDialog.Builder dialogBuilder(Activity activity) {
        return new AlertDialog.Builder(activity, android.R.style.Theme_Material_Light_Dialog_Alert);
    }

    static boolean isAlive(Activity activity) {
        if (activity.isFinishing()) {
            return false;
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !activity.isDestroyed();
    }

    static void toast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }
}
