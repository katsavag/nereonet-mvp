package com.nereonix.nereonet_mvp.core.vetting;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * SIRE 2.0 inspections (P41). "Latest" is a vessel's inspection with no later one (by inspection date, then id): the
 * one the Vetting list, the vessel's card, the sidebar counter and the Overview look at.
 */
public interface SireInspectionRepository extends JpaRepository<SireInspection, Long> {

    /** i is its vessel's latest inspection. */
    String LATEST = " not exists (select j.id from SireInspection j where j.ship = i.ship"
            + " and (j.inspectionDate > i.inspectionDate or (j.inspectionDate = i.inspectionDate and j.id > i.id))) ";

    /** i's report is no longer current or ends within 60 days, or its operator comments are due within 3 days. */
    String NEEDS_ATTENTION = " (i.inspectionDate <= :currentCutoff"
            + " or (i.status = com.nereonix.nereonet_mvp.core.vetting.SireStatus.DRAFT_RECEIVED"
            + " and coalesce(i.publishedOn, i.inspectionDate) <= :commentsCutoff)) ";

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

    /** The vessels of findLatestNeedingAttention, for the sidebar counter (nothing loaded to count). */
    @Query("select distinct i.ship.id from SireInspection i where i.ship.active = true and" + LATEST + "and"
            + NEEDS_ATTENTION)
    List<Long> findShipIdsNeedingAttention(LocalDate currentCutoff, LocalDate commentsCutoff);
}
