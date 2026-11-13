package com.nereonix.nereonet_mvp.core.vetting;

/**
 * How a SIRE 2.0 observation stands today (P41), always an icon and words: CLOSED once it has a closed date (ok),
 * OVERDUE while open after its target date (danger), else OPEN (info).
 */
public enum ObservationStatus {
    OPEN, OVERDUE, CLOSED;

    public String tone() {
        return switch (this) {
            case OPEN -> "info";
            case OVERDUE -> "danger";
            case CLOSED -> "ok";
        };
    }

    public String icon() {
        return switch (this) {
            case OPEN -> "clock";
            case OVERDUE -> "alert";
            case CLOSED -> "check";
        };
    }

    public String messageKey() {
        return "sire.observation.status." + name();
    }
}
