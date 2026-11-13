package com.nereonix.nereonet_mvp.core.vetting;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.nereonix.nereonet_mvp.core.alert.Alert;
import com.nereonix.nereonet_mvp.core.alert.AlertKind;
import com.nereonix.nereonet_mvp.core.alert.AlertLevels;

/**
 * A SIRE 2.0 inspection as the pages show it (P41): a vessel's latest one (or, on the SIRE page, an earlier one the
 * office opened), its observations, the company policy (SireSettings) and today's UTC date. inspection is null when
 * the vessel has none. The rules, in one place:
 * <ul>
 *   <li>Report current until: the inspection date + the policy's months. SireReportState: ending within 60 days
 *       (warning), no longer current after it (danger).</li>
 *   <li>Operator comments due: while the status is Draft received, the published date (else the inspection date)
 *       + the policy's days. Due within 3 days, or overdue, needs attention.</li>
 *   <li>Observations: open until they have a closed date; overdue while open after their target date.</li>
 *   <li>Needs attention: the report ending or no longer current, comments due within 3 days, or an overdue
 *       observation.</li>
 * </ul>
 */
public record SireView(SireInspection inspection, List<SireObservation> observations, SireSettings settings,
                       LocalDate today) {

    /** One observation row: the observation and its status today. */
    public record Row(SireObservation observation, ObservationStatus status) {
    }

    /** The observations of one VIQ chapter, by question number. */
    public record ChapterGroup(int chapter, List<Row> rows) {

        public String messageKey() {
            return "sire.chapter." + chapter;
        }
    }

    /** The number of observations of one type. */
    public record TypeCount(ObservationType type, long count) {
    }

    public SireView {
        observations = observations == null ? List.of() : List.copyOf(observations);
    }

    public boolean hasData() {
        return inspection != null;
    }

    public LocalDate inspectionDate() {
        return inspection == null ? null : inspection.getInspectionDate();
    }

    public SireStatus status() {
        return inspection == null ? null : inspection.getStatus();
    }

    // The report

    public LocalDate currentUntil() {
        return inspection == null ? null : settings.currentUntil(inspection.getInspectionDate());
    }

    public SireReportState reportState() {
        LocalDate until = currentUntil();
        if (until == null) {
            return SireReportState.NONE;
        }
        if (until.isBefore(today)) {
            return SireReportState.NOT_CURRENT;
        }
        return until.isAfter(today.plusDays(SireReportState.ENDING_DAYS)) ? SireReportState.CURRENT
                : SireReportState.ENDING;
    }

    /** Days from today to the current-until date (negative once it has passed); 0 without an inspection. */
    public long currentDaysLeft() {
        LocalDate until = currentUntil();
        return until == null ? 0 : ChronoUnit.DAYS.between(today, until);
    }

    // The operator comments

    /** When the operator comments are due: only while the status is Draft received, else null. */
    public LocalDate commentsDueOn() {
        if (inspection == null || inspection.getStatus() != SireStatus.DRAFT_RECEIVED) {
            return null;
        }
        LocalDate from = inspection.getPublishedOn() != null ? inspection.getPublishedOn() : inspection.getInspectionDate();
        return from.plusDays(settings.commentDays());
    }

    /** Days from today to the comment deadline (negative once passed); 0 without one. */
    public long commentsDaysLeft() {
        LocalDate due = commentsDueOn();
        return due == null ? 0 : ChronoUnit.DAYS.between(today, due);
    }

    /** The comments are due within 3 days, or overdue. */
    public boolean commentsUrgent() {
        return commentsDueOn() != null && commentsDaysLeft() <= SireSettings.COMMENTS_DUE_DAYS;
    }

    public boolean commentsOverdue() {
        return commentsDueOn() != null && commentsDaysLeft() < 0;
    }

    /** The comment banner's tone: danger once overdue, warning within 3 days, else info. */
    public String commentsTone() {
        return commentsOverdue() ? "danger" : commentsUrgent() ? "warning" : "info";
    }

    // Observations

    public long total() {
        return observations.size();
    }

    /** Not closed: open or overdue. */
    public long open() {
        return observations.stream().filter(o -> o.getClosedOn() == null).count();
    }

    public long overdue() {
        return observations.stream().filter(o -> o.status(today) == ObservationStatus.OVERDUE).count();
    }

    public List<TypeCount> typeCounts() {
        return java.util.Arrays.stream(ObservationType.values())
                .map(t -> new TypeCount(t, observations.stream().filter(o -> o.getType() == t).count())).toList();
    }

    /** The observations (all, or only the ones not closed) by VIQ chapter, then question number. */
    public List<ChapterGroup> chapters(boolean all) {
        Map<Integer, List<Row>> byChapter = new TreeMap<>();
        observations.stream()
                .filter(o -> all || o.getClosedOn() == null)
                .sorted(Comparator.comparing(SireObservation::getQuestionNumber, SireView::compareQuestions)
                        .thenComparing(SireObservation::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(o -> byChapter.computeIfAbsent(o.getChapter(), c -> new ArrayList<>())
                        .add(new Row(o, o.status(today))));
        return byChapter.entrySet().stream().map(e -> new ChapterGroup(e.getKey(), List.copyOf(e.getValue()))).toList();
    }

    // The list and the Overview

    public boolean needsAttention() {
        return reportState().needsAttention() || commentsUrgent() || overdue() > 0;
    }

    /**
     * The tone of the Vetting list's one badge, which names the first of: comments overdue (danger), comments due
     * within 3 days (warning), report no longer current (danger), observations overdue (danger), report ending
     * (warning).
     */
    public String attentionTone() {
        if (commentsOverdue()) {
            return "danger";
        }
        if (commentsUrgent()) {
            return "warning";
        }
        return reportState() == SireReportState.NOT_CURRENT || overdue() > 0 ? "danger" : "warning";
    }

    /** The Vetting list's urgency: 0 report not current, comments due within 3 days or overdue observations; 1
     *  report ending within 60 days; 2 the rest (as RightShipView.urgency). */
    public int urgency() {
        if (reportState() == SireReportState.NOT_CURRENT || commentsUrgent() || overdue() > 0) {
            return 0;
        }
        return reportState() == SireReportState.ENDING ? 1 : 2;
    }

    // Alerts (P43)

    /** The report's level (AlertLevels): 0 no longer current, 1 ending within 30 days, 2 within 60; NONE beyond. */
    public int reportLevel() {
        return inspection == null ? AlertLevels.NONE : AlertLevels.ofDate(currentUntil(), today);
    }

    /** The operator comments' level: 0 overdue, 1 due; NONE without a deadline. */
    public int commentsLevel() {
        return commentsDueOn() == null ? AlertLevels.NONE : commentsOverdue() ? AlertLevels.EXPIRED : 1;
    }

    /**
     * The inspection's alerts right now: the comments due within 3 days or overdue and the report ending or no
     * longer current (the inspection's id), and each overdue observation (its id).
     */
    public List<Alert> alerts() {
        List<Alert> alerts = new ArrayList<>();
        if (inspection == null) {
            return alerts;
        }
        if (commentsUrgent()) {
            alerts.add(Alert.of(AlertKind.SIRE_COMMENTS, inspection.getId(), commentsLevel()));
        }
        if (reportState().needsAttention()) {
            alerts.add(Alert.of(AlertKind.SIRE_REPORT, inspection.getId(), reportLevel()));
        }
        for (SireObservation o : observations) {
            if (o.status(today) == ObservationStatus.OVERDUE) {
                alerts.add(Alert.of(AlertKind.SIRE_OBSERVATION, o.getId(), AlertLevels.EXPIRED));
            }
        }
        return alerts;
    }

    // "5.4.1" before "5.10.1": by each number, then as text.
    static int compareQuestions(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c = compareParts(x[i], y[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }

    private static int compareParts(String a, String b) {
        if (a.matches("\\d{1,9}") && b.matches("\\d{1,9}")) {
            return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        }
        return a.compareToIgnoreCase(b);
    }
}
