package com.limelight.selfframed.update;

/**
 * Compares fork version names of the form "<upstream>-sf.<revision>", e.g. "12.2-sf.1" or
 * "12.3.1-sf.4". Upstream parts are compared numerically component by component (missing
 * components count as 0), then the fork revision. Pure Java, unit-tested.
 */
public final class VersionNames {
    private VersionNames() {
    }

    /** @return numbers of the upstream part followed by the revision, or null if malformed */
    public static int[] parse(String versionName) {
        if (versionName == null) {
            return null;
        }
        int sep = versionName.indexOf("-sf.");
        if (sep <= 0) {
            return null;
        }
        String[] upstream = versionName.substring(0, sep).split("\\.");
        String revision = versionName.substring(sep + "-sf.".length());
        int[] out = new int[upstream.length + 1];
        try {
            for (int i = 0; i < upstream.length; i++) {
                out[i] = parseNonNegative(upstream[i]);
            }
            out[upstream.length] = parseNonNegative(revision);
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    private static int parseNonNegative(String s) {
        if (s.isEmpty() || s.length() > 9 || !s.matches("[0-9]+")) {
            throw new NumberFormatException(s);
        }
        return Integer.parseInt(s);
    }

    /**
     * @return &gt;0 if a is newer than b, &lt;0 if older, 0 if equal or if either cannot be
     *         parsed (an unparseable version is never treated as an update)
     */
    public static int compare(String a, String b) {
        int[] pa = parse(a), pb = parse(b);
        if (pa == null || pb == null) {
            return 0;
        }
        int upstreamLen = Math.max(pa.length, pb.length) - 1;
        for (int i = 0; i < upstreamLen; i++) {
            int x = i < pa.length - 1 ? pa[i] : 0;
            int y = i < pb.length - 1 ? pb[i] : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return Integer.compare(pa[pa.length - 1], pb[pb.length - 1]);
    }
}
