package com.limelight.selfframed.update;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A published release of this fork as reported by the GitHub REST API
 * (GET /repos/{owner}/{repo}/releases/latest), reduced to what the updater needs.
 *
 * Pure Java (org.json only), unit-tested on the JVM.
 */
public final class ReleaseInfo {
    public final String versionName;
    public final String tag;
    public final String apkName;
    public final String apkUrl;
    public final long apkSize;
    /** Lower-case hex SHA-256 of the APK, or null if GitHub did not report a digest. */
    public final String sha256;
    public final String notes;
    public final String pageUrl;

    private ReleaseInfo(String versionName, String tag, String apkName, String apkUrl, long apkSize, String sha256,
                        String notes, String pageUrl) {
        this.versionName = versionName;
        this.tag = tag;
        this.apkName = apkName;
        this.apkUrl = apkUrl;
        this.apkSize = apkSize;
        this.sha256 = sha256;
        this.notes = notes;
        this.pageUrl = pageUrl;
    }

    /**
     * Parses a release object.
     *
     * @param apkSuffix expected end of the asset name, e.g. "-nonRoot-release.apk"
     * @return the release, or null if it is a draft/prerelease, the tag is not a fork tag
     *         ("v" + versionName with "-sf."), or no matching APK is attached
     */
    public static ReleaseInfo parse(JSONObject release, String apkSuffix) {
        if (release == null || release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) {
            return null;
        }
        String tag = release.optString("tag_name", "");
        if (!tag.startsWith("v") || !tag.contains("-sf.")) {
            return null;
        }
        String versionName = tag.substring(1);
        if (VersionNames.parse(versionName) == null) {
            return null;
        }

        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) {
            return null;
        }
        List<JSONObject> candidates = new ArrayList<>();
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) {
                continue;
            }
            String name = a.optString("name", "");
            if (name.endsWith(apkSuffix) && name.contains(versionName)) {
                candidates.add(a);
            }
        }
        if (candidates.size() != 1) {
            // None, or ambiguous: better no update than the wrong file
            return null;
        }
        JSONObject apk = candidates.get(0);
        String url = apk.optString("browser_download_url", "");
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            return null;
        }

        String sha256 = null;
        String digest = apk.optString("digest", "");
        if (digest.startsWith("sha256:")) {
            String hex = digest.substring("sha256:".length()).toLowerCase(Locale.ROOT);
            if (hex.matches("[0-9a-f]{64}")) {
                sha256 = hex;
            }
        }

        return new ReleaseInfo(versionName, tag, apk.optString("name"), url, apk.optLong("size", -1), sha256,
                release.optString("body", ""), release.optString("html_url", ""));
    }

    /** True if this release is newer than the installed version name. */
    public boolean isNewerThan(String installedVersionName) {
        return VersionNames.compare(versionName, installedVersionName) > 0;
    }
}
