package com.nereonix.nereonet_mvp.alert;

import java.time.LocalDate;

import com.nereonix.nereonet_mvp.core.alert.Alert;
import com.nereonix.nereonet_mvp.core.alert.AlertKind;
import com.nereonix.nereonet_mvp.core.alert.AlertLevels;
import com.nereonix.nereonet_mvp.core.ship.Ship;

/**
 * One alert as the snooze page reviews it (P43): what it is right now (its key and level), the vessel it concerns,
 * and what names it: the certificate type or the SIRE question (subject), the person for a certificate or missing
 * documents, the date that drives it (a certificate's expiry, an inspection's valid until, a report's current until,
 * the comments' due date, an observation's target), and a number (missing documents, the Safety Score).
 * expired: the date has passed (or the item is as urgent): snoozed for at most 30 days, never "until it expires".
 */
public record AlertView(Alert alert, Ship ship, String subject, String person, String personEmail, LocalDate date,
                        Long number) {

    public AlertKind kind() {
        return alert.key().kind();
    }

    public boolean expired() {
        return alert.level() == AlertLevels.EXPIRED || kind() == AlertKind.DOCUMENTS_MISSING;
    }

    /** "Until it expires" is offered: a date in the future. */
    public boolean canWaitForDate() {
        return date != null && !expired();
    }

    /** For the audit log: "Certificate expiring: STCW Basic Safety Training of jane@example.com". */
    public String auditTitle() {
        StringBuilder text = new StringBuilder(kind().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
        text.append(": ");
        if (subject != null) {
            text.append(subject);
        }
        if (personEmail != null) {
            text.append(subject != null ? " of " : "").append(personEmail);
        }
        if (number != null) {
            text.append(" (").append(number).append(")");
        }
        if (ship != null) {
            text.append(" on ").append(ship.getName());
        }
        return text.toString();
    }
}
