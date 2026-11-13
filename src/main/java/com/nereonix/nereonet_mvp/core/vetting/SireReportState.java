package com.nereonix.nereonet_mvp.core.vetting;

/**
 * Whether a vessel's latest SIRE 2.0 report is still current today (P41): it is treated as current for
 * SireSettings.reportCurrentMonths after the inspection (company policy, not an OCIMF rule).
 * <ul>
 *   <li>CURRENT: current until a date more than ENDING_DAYS away (ok);</li>
 *   <li>ENDING: current until today or within ENDING_DAYS (warning, the P02 tone);</li>
 *   <li>NOT_CURRENT: the date has passed (danger);</li>
 *   <li>NONE: no inspection (neutral).</li>
 * </ul>
 */
public enum SireReportState {
    CURRENT, ENDING, NOT_CURRENT, NONE;

    /** A report current for this many days or fewer is "ending" (the sidebar counter, the Overview). */
    public static final int ENDING_DAYS = InspectionState.EXPIRING_DAYS;

    public String tone() {
        return switch (this) {
            case CURRENT -> "ok";
            case ENDING -> "warning";
            case NOT_CURRENT -> "danger";
            case NONE -> "neutral";
        };
    }

    public String icon() {
        return switch (this) {
            case CURRENT -> "check";
            case ENDING -> "clock";
            case NOT_CURRENT -> "alert";
            case NONE -> "dash";
        };
    }

    public boolean needsAttention() {
        return this == ENDING || this == NOT_CURRENT;
    }
}
