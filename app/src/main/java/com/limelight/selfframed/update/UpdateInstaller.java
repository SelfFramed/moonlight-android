package com.limelight.selfframed.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.widget.TextView;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads a release APK and hands it to Android's PackageInstaller. Android asks the user to
 * confirm and refuses the update unless it is signed with the same key as the installed app;
 * we check package name, version and signature beforehand anyway to give a clear message.
 */
public final class UpdateInstaller {
    private static final int TIMEOUT_MS = 20000;
    private static final long MAX_APK_BYTES = 200L * 1024 * 1024;
    static final String ACTION_STATUS = BuildConfig.APPLICATION_ID + ".SF_UPDATE_STATUS";

    /** Waiting for the user to allow installs from this app (Android 8+). UI thread only. */
    private static ReleaseInfo pendingRelease;

    private UpdateInstaller() {
    }

    /** Starts the update (UI thread). */
    public static void start(Activity activity, ReleaseInfo release) {
        if (!canInstall(activity)) {
            askForInstallPermission(activity, release);
            return;
        }
        download(activity, release);
    }

    /**
     * Called when the main screen comes back, e.g. from the "install unknown apps" settings.
     *
     * @return true if a pending update was resumed
     */
    static boolean resumePendingInstall(Activity activity) {
        if (pendingRelease == null || !canInstall(activity)) {
            return false;
        }
        ReleaseInfo release = pendingRelease;
        pendingRelease = null;
        download(activity, release);
        return true;
    }

    private static boolean canInstall(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.getPackageManager().canRequestPackageInstalls();
    }

    private static void askForInstallPermission(final Activity activity, final ReleaseInfo release) {
        pendingRelease = release;
        UpdateChecker.dialogBuilder(activity)
                .setTitle(R.string.sf_update_permission_title)
                .setMessage(R.string.sf_update_permission_message)
                .setPositiveButton(R.string.sf_update_open_settings, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName())));
                        }
                    }
                })
                .setNegativeButton(R.string.sf_update_cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        pendingRelease = null;
                    }
                })
                .show();
    }

    private static void download(final Activity activity, final ReleaseInfo release) {
        final File dir = new File(activity.getCacheDir(), "sf-update");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            UpdateChecker.toast(activity, activity.getString(R.string.sf_update_failed, "cache"));
            return;
        }
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        final File target = new File(dir, "update.apk");

        final TextView progress = new TextView(activity);
        progress.setTextColor(Color.BLACK);
        progress.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        int pad = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24,
                activity.getResources().getDisplayMetrics()));
        progress.setPadding(pad, pad, pad, pad);
        progress.setText(activity.getString(R.string.sf_update_downloading, "…"));

        final AtomicBoolean cancelled = new AtomicBoolean();
        final AlertDialog dialog = UpdateChecker.dialogBuilder(activity)
                .setTitle(activity.getString(R.string.sf_update_title_version, release.versionName))
                .setView(progress)
                .setCancelable(false)
                .setNegativeButton(R.string.sf_update_cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        cancelled.set(true);
                    }
                })
                .show();

        new Thread("SF update download") {
            @Override
            public void run() {
                String error = null;
                try {
                    downloadTo(release, target, cancelled, new Progress() {
                        long lastShown;

                        @Override
                        public void onProgress(final long done, final long total) {
                            long now = System.currentTimeMillis();
                            // E-ink: update the text at most every 750 ms
                            if (now - lastShown < 750 && done < total) {
                                return;
                            }
                            lastShown = now;
                            activity.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    progress.setText(activity.getString(R.string.sf_update_downloading,
                                            String.format(Locale.getDefault(), "%.1f / %.1f MB",
                                                    done / 1048576.0, Math.max(done, total) / 1048576.0)));
                                }
                            });
                        }
                    });
                    verify(activity, release, target);
                } catch (IOException | RuntimeException e) {
                    error = e.getMessage() != null ? e.getMessage() : e.toString();
                    LimeLog.warning("SelfFramed update: " + e);
                    //noinspection ResultOfMethodCallIgnored
                    target.delete();
                }
                final String failure = error;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (dialog.isShowing()) {
                            dialog.dismiss();
                        }
                        if (cancelled.get()) {
                            return;
                        }
                        if (failure != null) {
                            if (!UpdateChecker.isAlive(activity)) {
                                return;
                            }
                            // Mobile data on the road can drop; let the user retry right here
                            UpdateChecker.dialogBuilder(activity)
                                    .setTitle(activity.getString(R.string.sf_update_title_version, release.versionName))
                                    .setMessage(activity.getString(R.string.sf_update_failed, failure))
                                    .setPositiveButton(R.string.sf_update_retry, new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface d, int which) {
                                            download(activity, release);
                                        }
                                    })
                                    .setNegativeButton(R.string.sf_update_cancel, null)
                                    .show();
                        } else {
                            install(activity, target);
                        }
                    }
                });
            }
        }.start();
    }

    interface Progress {
        void onProgress(long done, long total);
    }

    private static void downloadTo(ReleaseInfo release, File target, AtomicBoolean cancelled, Progress progress)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(release.apkUrl).openConnection();
        try {
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/octet-stream");
            conn.setRequestProperty("User-Agent", "Moonlight-E-Ink/" + BuildConfig.VERSION_NAME);
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            long total = release.apkSize > 0 ? release.apkSize : conn.getContentLength();
            if (total > MAX_APK_BYTES) {
                throw new IOException("file too large");
            }
            long done = 0;
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled.get()) {
                        throw new IOException("cancelled");
                    }
                    out.write(buf, 0, n);
                    done += n;
                    if (done > MAX_APK_BYTES) {
                        throw new IOException("file too large");
                    }
                    progress.onProgress(done, total);
                }
            }
            if (release.apkSize > 0 && done != release.apkSize) {
                throw new IOException("incomplete download (" + done + " of " + release.apkSize + " bytes)");
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Checksum (if GitHub reported one), package name, version and signing key. */
    private static void verify(Context context, ReleaseInfo release, File apk) throws IOException {
        if (release.sha256 != null) {
            String actual = sha256(apk);
            if (!release.sha256.equals(actual)) {
                throw new IOException("checksum mismatch");
            }
        }

        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES : getSignaturesFlag();
        PackageInfo update = pm.getPackageArchiveInfo(apk.getPath(), flags);
        if (update == null) {
            throw new IOException("not a valid APK");
        }
        if (!context.getPackageName().equals(update.packageName)) {
            throw new IOException("wrong app: " + update.packageName);
        }
        try {
            PackageInfo installed = pm.getPackageInfo(context.getPackageName(), flags);
            if (versionCode(update) <= versionCode(installed)) {
                throw new IOException("not newer than the installed version");
            }
            // Only a positive mismatch aborts here; if a platform cannot read archive signatures,
            // Android's own check during installation still decides
            Signature[] have = signers(installed), got = signers(update);
            if (have.length > 0 && got.length > 0 && !Arrays.equals(have, got)) {
                throw new IOException(context.getString(R.string.sf_update_signature_mismatch));
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException(e);
        }
    }

    @SuppressWarnings("deprecation")
    private static int getSignaturesFlag() {
        return PackageManager.GET_SIGNATURES;
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signers(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (info.signingInfo == null) {
                return new Signature[0];
            }
            return info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners() : info.signingInfo.getSigningCertificateHistory();
        }
        return info.signatures != null ? info.signatures : new Signature[0];
    }

    private static String sha256(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** Streams the APK into an install session; Android then asks the user to confirm. */
    private static void install(Activity activity, File apk) {
        try {
            PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(activity.getPackageName());
            int sessionId = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                try (OutputStream out = session.openWrite("base.apk", 0, apk.length());
                     InputStream in = new FileInputStream(apk)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    session.fsync(out);
                }
                Intent status = new Intent(activity, UpdateStatusReceiver.class).setAction(ACTION_STATUS);
                int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pending = PendingIntent.getBroadcast(activity, sessionId, status, piFlags);
                session.commit(pending.getIntentSender());
            }
        } catch (IOException | RuntimeException e) {
            LimeLog.warning("SelfFramed update: install session failed: " + e);
            UpdateChecker.toast(activity, activity.getString(R.string.sf_update_failed, String.valueOf(e.getMessage())));
        }
    }
}
