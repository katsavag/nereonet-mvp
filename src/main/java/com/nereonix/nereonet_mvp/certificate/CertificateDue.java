package com.nereonix.nereonet_mvp.certificate;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;

/**
 * The Status filter of the Certificates list (P19), the same steps as the expiry badges: Expired, then the 30, 60 and
 * 90 day reminder thresholds (UTC days). Sent as ?due=key; labels certificate.filter.due.<key>.
 */
public enum CertificateDue {

    EXPIRED("expired", null, -1),
    DAYS_30("30", 0, 30),
    DAYS_60("60", 31, 60),
    DAYS_90("90", 61, 90);

    private final String key;
    private final Integer fromDays;
    private final int toDays;

    CertificateDue(String key, Integer fromDays, int toDays) {
        this.key = key;
        this.fromDays = fromDays;
        this.toDays = toDays;
    }

    public String key() {
        return key;
    }

    /** The first expiry date in this step, or null for Expired (no lower bound). */
    LocalDate from(LocalDate today) {
        return fromDays == null ? null : today.plusDays(fromDays);
    }

    /** The last expiry date in this step, inclusive. */
    LocalDate to(LocalDate today) {
        return today.plusDays(toDays);
    }

    /** The step with this key; empty for null or anything else, so an unknown value shows every certificate. */
    public static Optional<CertificateDue> of(String key) {
        return Arrays.stream(values()).filter(due -> due.key.equals(key)).findFirst();
    }
}
