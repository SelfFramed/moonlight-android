package com.limelight.selfframed.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class UpdateLogicTest {
    private static final String SUFFIX = "-nonRoot-release.apk";
    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    public void versionOrdering() {
        assertTrue(VersionNames.compare("12.2-sf.2", "12.2-sf.1") > 0);
        assertTrue(VersionNames.compare("12.3-sf.1", "12.2-sf.9") > 0);
        assertTrue(VersionNames.compare("12.10-sf.1", "12.9-sf.1") > 0);
        assertTrue(VersionNames.compare("12.2.1-sf.1", "12.2-sf.5") > 0);
        assertTrue(VersionNames.compare("13.0-sf.1", "12.99-sf.99") > 0);
        assertEquals(0, VersionNames.compare("12.2-sf.1", "12.2.0-sf.1"));
        assertTrue(VersionNames.compare("12.2-sf.1", "12.2-sf.2") < 0);
    }

    @Test
    public void malformedVersionsAreNeverAnUpdate() {
        for (String bad : new String[] {null, "", "12.2", "12.2-sf.", "12.2-sf.x", "v12.2-sf.1", "12.a-sf.1",
                "-sf.1", "12..2-sf.1"}) {
            assertNull(bad, VersionNames.parse(bad));
            assertEquals(bad, 0, VersionNames.compare(bad, "12.2-sf.1"));
            assertEquals(bad, 0, VersionNames.compare("12.2-sf.1", bad));
        }
    }

    private static JSONObject release(String tag, String... assetNames) throws Exception {
        JSONArray assets = new JSONArray();
        for (String name : assetNames) {
            assets.put(new JSONObject()
                    .put("name", name)
                    .put("size", 11187559)
                    .put("digest", "sha256:" + SHA.toUpperCase())
                    .put("browser_download_url",
                            "https://github.com/SelfFramed/moonlight-android/releases/download/" + tag + "/" + name));
        }
        return new JSONObject()
                .put("tag_name", tag)
                .put("draft", false)
                .put("prerelease", false)
                .put("body", "Notes")
                .put("html_url", "https://github.com/SelfFramed/moonlight-android/releases/tag/" + tag)
                .put("assets", assets);
    }

    @Test
    public void parsesGitHubRelease() throws Exception {
        ReleaseInfo r = ReleaseInfo.parse(release("v12.2-sf.2",
                "moonlight-eink-12.2-sf.2-nonRoot-release.apk", "moonlight-eink-12.2-sf.2-nonRoot-debug.apk"), SUFFIX);
        assertNotNull(r);
        assertEquals("12.2-sf.2", r.versionName);
        assertEquals("moonlight-eink-12.2-sf.2-nonRoot-release.apk", r.apkName);
        assertEquals(11187559, r.apkSize);
        assertEquals(SHA, r.sha256);
        assertTrue(r.isNewerThan("12.2-sf.1"));
        assertFalse(r.isNewerThan("12.2-sf.2"));
        assertFalse(r.isNewerThan("12.3-sf.1"));
    }

    @Test
    public void rejectsWhatMustNotBeInstalled() throws Exception {
        // Upstream tag, not a fork release
        assertNull(ReleaseInfo.parse(release("v12.3", "moonlight-eink-12.3-nonRoot-release.apk"), SUFFIX));
        // No release APK (only debug)
        assertNull(ReleaseInfo.parse(release("v12.2-sf.2", "moonlight-eink-12.2-sf.2-nonRoot-debug.apk"), SUFFIX));
        // APK of another version attached by mistake
        assertNull(ReleaseInfo.parse(release("v12.2-sf.2", "moonlight-eink-12.2-sf.1-nonRoot-release.apk"), SUFFIX));
        // Ambiguous
        assertNull(ReleaseInfo.parse(release("v12.2-sf.2", "a-12.2-sf.2-nonRoot-release.apk",
                "b-12.2-sf.2-nonRoot-release.apk"), SUFFIX));
        // Prerelease / draft
        assertNull(ReleaseInfo.parse(release("v12.2-sf.2", "moonlight-eink-12.2-sf.2-nonRoot-release.apk")
                .put("prerelease", true), SUFFIX));
        assertNull(ReleaseInfo.parse(release("v12.2-sf.2", "moonlight-eink-12.2-sf.2-nonRoot-release.apk")
                .put("draft", true), SUFFIX));
        assertNull(ReleaseInfo.parse(null, SUFFIX));
    }

    @Test
    public void missingOrOddDigestMeansNoChecksum() throws Exception {
        JSONObject json = release("v12.2-sf.2", "moonlight-eink-12.2-sf.2-nonRoot-release.apk");
        json.getJSONArray("assets").getJSONObject(0).remove("digest");
        assertNull(ReleaseInfo.parse(json, SUFFIX).sha256);
        json.getJSONArray("assets").getJSONObject(0).put("digest", "md5:abc");
        assertNull(ReleaseInfo.parse(json, SUFFIX).sha256);
    }
}
