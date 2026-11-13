package com.nereonix.nereonet_mvp.core.vetting;

import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The company's SIRE 2.0 policy (P41), app.sire.*, shown on the SIRE page as "company policy". These are this
 * company's own choices, not OCIMF rules:
 *   report-current-months  how long a report is treated as current after the inspection (default 12)
 *   comment-days           how many days the operator has for its comments on a draft report, counted from the
 *                          report's published date, else the inspection date (default 14)
 * A value below 1 falls back to the default.
 */
@Component
public class SireSettings {

    public static final int DEFAULT_REPORT_CURRENT_MONTHS = 12;
    public static final int DEFAULT_COMMENT_DAYS = 14;

    /** Operator comments due within this many days (or overdue) need attention: the counter, the Overview. */
    public static final int COMMENTS_DUE_DAYS = 3;

    private final int reportCurrentMonths;
    private final int commentDays;

    public SireSettings(@Value("${app.sire.report-current-months:12}") int reportCurrentMonths,
                        @Value("${app.sire.comment-days:14}") int commentDays) {
        this.reportCurrentMonths = reportCurrentMonths < 1 ? DEFAULT_REPORT_CURRENT_MONTHS : reportCurrentMonths;
        this.commentDays = commentDays < 1 ? DEFAULT_COMMENT_DAYS : commentDays;
    }

    public int reportCurrentMonths() {
        return reportCurrentMonths;
    }

    public int commentDays() {
        return commentDays;
    }

    /** The date until which the report of an inspection on this date is current. */
    public LocalDate currentUntil(LocalDate inspectionDate) {
        return inspectionDate.plusMonths(reportCurrentMonths);
    }

    /**
     * The latest inspection date whose report is current until "until" or earlier: an inspection on this date or
     * before has currentUntil(date) <= until. (Months are uneven, so this steps forward from until minus the months.)
     */
    public LocalDate latestInspectionCurrentUntil(LocalDate until) {
        LocalDate date = until.minusMonths(reportCurrentMonths);
        while (!currentUntil(date.plusDays(1)).isAfter(until)) {
            date = date.plusDays(1);
        }
        while (currentUntil(date).isAfter(until)) {
            date = date.minusDays(1);
        }
        return date;
    }

    /** The repositories' currentCutoff: inspected on it or before, the report ends within 60 days or has ended. */
    public LocalDate currentCutoff(LocalDate today) {
        return latestInspectionCurrentUntil(today.plusDays(SireReportState.ENDING_DAYS));
    }

    /** The repositories' commentsCutoff: a draft with this base date or earlier has its comments due within 3 days. */
    public LocalDate commentsCutoff(LocalDate today) {
        return today.plusDays(COMMENTS_DUE_DAYS).minusDays(commentDays);
    }
}
