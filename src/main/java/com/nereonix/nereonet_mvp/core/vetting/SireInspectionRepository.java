package com.nereonix.nereonet_mvp.core.vetting;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.nereonix.nereonet_mvp.core.alert.AlertKind;

/**
 * SIRE 2.0 inspections (P41). "Latest" is a vessel's inspection with no later one (by inspection date, then id): the
 * one the Vetting list, the vessel's card, the sidebar counter and the Overview look at.
 */
public interface SireInspectionRepository extends JpaRepository<SireInspection, Long> {

    /** i is its vessel's latest inspection. */
    String LATEST = " not exists (select j.id from SireInspection j where j.ship = i.ship"
            + " and (j.inspectionDate > i.inspectionDate or (j.inspectionDate = i.inspectionDate and j.id > i.id))) ";

    /** i's report is no longer current or ends within 60 days, or its operator comments are due within 3 days. */
    String REPORT_DUE = " i.inspectionDate <= :currentCutoff ";
    String COMMENTS_DUE = " (i.status = com.nereonix.nereonet_mvp.core.vetting.SireStatus.DRAFT_RECEIVED"
            + " and coalesce(i.publishedOn, i.inspectionDate) <= :commentsCutoff) ";
    String NEEDS_ATTENTION = " (" + REPORT_DUE + " or " + COMMENTS_DUE + ") ";

    /**
     * The same, less what a snooze that holds today puts aside (P43): a SIRE_REPORT snooze at the report's level
     * (0 no longer current: inspected on notCurrentCutoff or before; 1 ending within 30 days: on cutoff30 or before;
     * else 2), a SIRE_COMMENTS snooze at the comments' level (0 overdue: the base date on overdueCutoff or before,
     * else 1). AlertLevels; SireSettings works out the cut-offs.
     */
    String NEEDS_ATTENTION_NOT_SNOOZED = " ((" + REPORT_DUE
            + " and not exists (select a.id from AlertSnooze a where a.kind = :reportKind and a.subjectId = i.id"
            + "   and a.endedAt is null and a.until >= :today"
            + "   and a.level <= case when i.inspectionDate <= :notCurrentCutoff then 0"
            + "                       when i.inspectionDate <= :cutoff30 then 1 else 2 end))"
            + " or (" + COMMENTS_DUE
            + " and not exists (select a.id from AlertSnooze a where a.kind = :commentsKind and a.subjectId = i.id"
            + "   and a.endedAt is null and a.until >= :today"
            + "   and a.level <= case when coalesce(i.publishedOn, i.inspectionDate) <= :overdueCutoff then 0 else 1 end))) ";

    /** A vessel's inspections, newest first: the first is the latest. */
    List<SireInspection> findAllByShipIdOrderByInspectionDateDescIdDesc(Long shipId);

    Optional<SireInspection> findByIdAndShipId(Long id, Long shipId);

    /** The latest inspection of every vessel that has one (the Vetting list, the Vessels list). */
    @Query("select i from SireInspection i join fetch i.ship where" + LATEST)
    List<SireInspection> findAllLatest();

    /**
     * The latest inspections of active vessels whose report needs attention (the Overview): inspected on currentCutoff
     * or before (so current until today + 60 days at the latest), or a draft whose base date for the operator comments
     * (publishedOn, else the inspection date) is commentsCutoff or before (so due within 3 days, or overdue).
     * SireSettings works out both cut-offs.
     */
    @Query("select i from SireInspection i join fetch i.ship s where s.active = true and" + LATEST + "and"
            + NEEDS_ATTENTION)
    List<SireInspection> findLatestNeedingAttention(LocalDate currentCutoff, LocalDate commentsCutoff);

    /** The vessels of findLatestNeedingAttention, for the sidebar counter (nothing loaded to count), less the items
     *  whose snooze holds (P43). */
    @Query("select distinct i.ship.id from SireInspection i where i.ship.active = true and" + LATEST + "and"
            + NEEDS_ATTENTION_NOT_SNOOZED)
    List<Long> findShipIdsNeedingAttention(LocalDate currentCutoff, LocalDate commentsCutoff, LocalDate today,
                                           LocalDate notCurrentCutoff, LocalDate cutoff30, LocalDate overdueCutoff,
                                           AlertKind reportKind, AlertKind commentsKind);
}
