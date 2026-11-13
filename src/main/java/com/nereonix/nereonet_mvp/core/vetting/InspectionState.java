package com.nereonix.nereonet_mvp.core.vetting;

import java.time.LocalDate;

/**
 * How a vessel's RightShip inspection stands today (P40), for the badges: always an icon and words, never colour alone.
 * <ul>
 *   <li>UNACCEPTABLE: the last inspection's outcome was Unacceptable (danger);</li>
 *   <li>EXPIRED: valid until a day before today (danger);</li>
 *   <li>EXPIRING: valid until today or within EXPIRING_DAYS (warning);</li>
 *   <li>VALID: valid until a later date (ok);</li>
 *   <li>NOT_REQUIRED: no date, and RightShip does not require one (neutral);</li>
 *   <li>NONE: no date known (neutral).</li>
 * </ul>
 */
public enum InspectionState {
    UNACCEPTABLE, EXPIRED, EXPIRING, VALID, NOT_REQUIRED, NONE;

    /** An inspection valid for this many days or fewer is "expiring" (the sidebar counter, the Overview). */
    public static final int EXPIRING_DAYS = 60;

    public static InspectionState of(InspectionOutcome outcome, LocalDate validUntil, Boolean required, LocalDate today) {
        if (outcome == InspectionOutcome.UNACCEPTABLE) {
            return UNACCEPTABLE;
        }
        if (validUntil == null) {
            return Boolean.FALSE.equals(required) ? NOT_REQUIRED : NONE;
        }
        if (validUntil.isBefore(today)) {
            return EXPIRED;
        }
        return validUntil.isAfter(today.plusDays(EXPIRING_DAYS)) ? VALID : EXPIRING;
    }

    /** The status colour of its badge: danger, warning, ok or neutral. */
    public String tone() {
        return switch (this) {
            case UNACCEPTABLE, EXPIRED -> "danger";
            case EXPIRING -> "warning";
            case VALID -> "ok";
            case NOT_REQUIRED, NONE -> "neutral";
        };
    }

    /** The badge's icon (layout/parts :: statusIcon): a warning triangle, a clock, a tick, or a dash for none. */
    public String icon() {
        return switch (this) {
            case UNACCEPTABLE, EXPIRED -> "alert";
            case EXPIRING -> "clock";
            case VALID -> "check";
            case NOT_REQUIRED, NONE -> "dash";
        };
    }

    /** Expired, unacceptable or expiring: the office has to act. */
    public boolean needsAttention() {
        return this == UNACCEPTABLE || this == EXPIRED || this == EXPIRING;
    }
}
