package com.nereonix.nereonet_mvp.requirement;

import java.time.LocalDate;
import java.util.Locale;

import com.nereonix.nereonet_mvp.core.certificate.Certificate;

/**
 * Where a seafarer stands on one required document (P42), from the certificate of that type that expires last:
 * Valid, Expiring (within EXPIRING_DAYS, today included), Expired (the date has passed, UTC days) or Missing (none).
 * Expired counts as missing in the totals ("5 of 7"). Listed in this order of urgency: missing first.
 * Each has its badge: tone, icon and words (requirement.state.&lt;NAME&gt;), never colour alone; export(): the
 * value in the .xls.
 */
public enum RequirementState {

    MISSING("danger", "alert"),
    EXPIRED("danger", "alert"),
    EXPIRING("warning", "clock"),
    VALID("ok", "check");

    /** Expiring: the certificate expires within this many days (the Overview's and the sidebar's 30 days). */
    public static final int EXPIRING_DAYS = 30;

    private final String tone;
    private final String icon;

    RequirementState(String tone, String icon) {
        this.tone = tone;
        this.icon = icon;
    }

    public String tone() {
        return tone;
    }

    public String icon() {
        return icon;
    }

    /** Missing or expired: what the totals count as missing and the crew are asked to see the office about. */
    public boolean needsAction() {
        return this == MISSING || this == EXPIRED;
    }

    public String export() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The state of a document held as this certificate (null: missing). today: the UTC date. */
    public static RequirementState of(Certificate certificate, LocalDate today) {
        if (certificate == null) {
            return MISSING;
        }
        if (certificate.isExpired(today)) {
            return EXPIRED;
        }
        return certificate.daysLeft(today) <= EXPIRING_DAYS ? EXPIRING : VALID;
    }
}
