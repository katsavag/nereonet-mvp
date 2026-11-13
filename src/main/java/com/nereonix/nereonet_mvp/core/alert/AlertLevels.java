package com.nereonix.nereonet_mvp.core.alert;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * How bad an alert is right now, as one number (P43): a snooze remembers the level of its item when it was snoozed
 * and ends by itself when the level drops ("it got worse"). Lower is worse.
 * <ul>
 *   <li>Dates (a certificate's expiry, an inspection's valid until, a SIRE report's current until, a target date):
 *       the reminder thresholds of P12. {@link #EXPIRED} once the date has passed, then 1 within 30 days, 2 within
 *       60, 3 within 90, {@link #NONE} beyond (nothing to alert about). So a snoozed "expiring in 45 days" ends when
 *       the 30-day threshold is crossed, and an "expiring" one when it expires.</li>
 *   <li>A Safety Score: the score itself (it drops).</li>
 *   <li>Required documents missing: minus the number missing (one more missing is worse).</li>
 *   <li>Operator comments: 1 while due, {@link #EXPIRED} once overdue. An overdue observation: {@link #EXPIRED}.</li>
 * </ul>
 * The SQL of the counters and lists (CertificateRepository, the vetting repositories) applies the same date buckets.
 */
public final class AlertLevels {

    /** The date has passed: expired, overdue, no longer current, unacceptable. */
    public static final int EXPIRED = 0;

    /** More than 90 days left: not an alert. */
    public static final int NONE = 4;

    private AlertLevels() {
    }

    /** The level of a date, counted in whole UTC days from today. */
    public static int ofDate(LocalDate date, LocalDate today) {
        return ofDaysLeft(ChronoUnit.DAYS.between(today, date));
    }

    public static int ofDaysLeft(long daysLeft) {
        if (daysLeft < 0) {
            return EXPIRED;
        }
        if (daysLeft <= 30) {
            return 1;
        }
        if (daysLeft <= 60) {
            return 2;
        }
        return daysLeft <= 90 ? 3 : NONE;
    }

    /** now is worse than the level the snooze remembers. */
    public static boolean worse(int now, int atSnooze) {
        return now < atSnooze;
    }
}
