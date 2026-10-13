package com.limelight.selfframed.battery;

/**
 * When to warn about the client's battery (pure Java, JVM-tested).
 *
 * <p>Bands: normal, low (at or below {@code lowPercent}) and critical (at or below
 * {@code criticalPercent}). While the phone is not charging, a warning is due whenever the band
 * gets worse than the worst band already warned about in this discharge: "low" is a short notice,
 * "critical" stays until charging starts, or until the user snoozes it (then it returns with the
 * next percent drop). Charging means the battery really charges (status charging or full), not
 * merely that a cable is plugged in: a lapdock that cannot keep up must not silence the warning.
 * Charging ends the discharge, re-arms both warnings and removes the critical notice.
 */
public final class BatteryWatch {
    /** A short notice to show after an update. */
    public enum Notice { NONE, LOW, CHARGING }

    private static final int NORMAL = 0;
    private static final int LOW = 1;
    private static final int CRITICAL = 2;

    private final int lowPercent;
    private final int criticalPercent;

    private boolean known;
    private boolean charging;
    private int percent = -1;
    private int warnedBand = NORMAL;
    private boolean criticalVisible;
    private int snoozedAt = -1;

    public BatteryWatch(int lowPercent, int criticalPercent) {
        this.lowPercent = Math.max(1, Math.min(100, lowPercent));
        this.criticalPercent = Math.max(0, Math.min(criticalPercent, this.lowPercent - 1));
    }

    /** A battery update (any order, repeats allowed); returns the short notice to show, if any. */
    public Notice update(int percent, boolean charging) {
        boolean first = !known;
        boolean wasCharging = this.charging;
        known = true;
        this.percent = percent;
        this.charging = charging;

        if (charging) {
            boolean hadWarning = warnedBand != NORMAL;
            warnedBand = NORMAL;
            criticalVisible = false;
            snoozedAt = -1;
            // Confirm that power arrives, but only as the answer to a warning
            return !first && !wasCharging && hadWarning ? Notice.CHARGING : Notice.NONE;
        }

        int band = percent <= criticalPercent ? CRITICAL : percent <= lowPercent ? LOW : NORMAL;
        if (band > warnedBand) {
            warnedBand = band;
            if (band == CRITICAL) {
                criticalVisible = true;
                snoozedAt = -1;
                return Notice.NONE;
            }
            return Notice.LOW;
        }
        if (warnedBand == CRITICAL && !criticalVisible && snoozedAt >= 0 && percent < snoozedAt) {
            criticalVisible = true;
            snoozedAt = -1;
        }
        return Notice.NONE;
    }

    /**
     * Hides the critical notice until the next percent drop.
     *
     * @return false if there was none to hide
     */
    public boolean snooze() {
        if (!criticalVisible) {
            return false;
        }
        criticalVisible = false;
        snoozedAt = percent;
        return true;
    }

    /** The critical notice should be on screen now. */
    public boolean criticalVisible() {
        return criticalVisible;
    }

    public boolean isKnown() {
        return known;
    }

    public int percent() {
        return percent;
    }

    public boolean isCharging() {
        return charging;
    }

    public int lowPercent() {
        return lowPercent;
    }

    public int criticalPercent() {
        return criticalPercent;
    }
}
