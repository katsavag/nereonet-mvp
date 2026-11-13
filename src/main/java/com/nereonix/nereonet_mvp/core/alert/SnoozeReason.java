package com.nereonix.nereonet_mvp.core.alert;

/** Why an alert was snoozed (P43), the one choice the form requires; OTHER needs a note. Labels: snooze.reason.<NAME>. */
public enum SnoozeReason {
    RENEWAL_IN_PROGRESS, SIGNS_OFF_BEFORE_EXPIRY, NOT_NEEDED_ON_VESSEL, OTHER;

    public String messageKey() {
        return "snooze.reason." + name();
    }
}
