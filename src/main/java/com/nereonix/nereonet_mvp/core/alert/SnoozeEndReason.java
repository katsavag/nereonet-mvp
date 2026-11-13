package com.nereonix.nereonet_mvp.core.alert;

/**
 * Why a snooze ended (P43): an office user undid it, its date passed, the item was resolved (a new certificate, a new
 * date, new vetting data that clears it), or the item got worse (expiring became expired, a reminder threshold was
 * crossed, a score dropped). The last three end by themselves (SnoozeService.sync). Labels: snooze.end.<NAME>.
 */
public enum SnoozeEndReason {
    UNDONE, DATE_PASSED, RESOLVED, WORSENED;

    public String messageKey() {
        return "snooze.end." + name();
    }
}
