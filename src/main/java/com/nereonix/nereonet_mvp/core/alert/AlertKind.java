package com.nereonix.nereonet_mvp.core.alert;

/**
 * The kinds of alert the office can snooze (P43), each with what its subject id names. Together they identify one
 * item: the same certificate expiring is one alert, that certificate expired another (so a snooze of the first
 * ends when the second takes its place: it got worse).
 * <ul>
 *   <li>CERTIFICATE_EXPIRING, CERTIFICATE_EXPIRED: a certificate (its id) within 90 days of expiry, or past it;</li>
 *   <li>DOCUMENTS_MISSING: a seafarer (user id) missing required documents (P42);</li>
 *   <li>VETTING_INSPECTION, VETTING_SCORE: a vessel (ship id): its RightShip inspection expiring, expired or
 *       unacceptable; its Safety Score of 2 or below (P40);</li>
 *   <li>SIRE_COMMENTS, SIRE_REPORT: a SIRE 2.0 inspection (its id): operator comments due or overdue, the report
 *       ending or no longer current (P41);</li>
 *   <li>SIRE_OBSERVATION: an observation (its id) past its target date.</li>
 * </ul>
 */
public enum AlertKind {

    CERTIFICATE_EXPIRING, CERTIFICATE_EXPIRED, DOCUMENTS_MISSING,
    VETTING_INSPECTION, VETTING_SCORE, SIRE_COMMENTS, SIRE_REPORT, SIRE_OBSERVATION;

    /** A certificate item: listed on Certificates. */
    public boolean certificate() {
        return this == CERTIFICATE_EXPIRING || this == CERTIFICATE_EXPIRED || this == DOCUMENTS_MISSING;
    }

    /** A vetting item: listed on Vetting. */
    public boolean vetting() {
        return !certificate();
    }

    /** The label's message key: "alert.kind.CERTIFICATE_EXPIRING". */
    public String messageKey() {
        return "alert.kind." + name();
    }
}
