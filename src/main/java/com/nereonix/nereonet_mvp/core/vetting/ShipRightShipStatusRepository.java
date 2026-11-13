package com.nereonix.nereonet_mvp.core.vetting;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.nereonix.nereonet_mvp.core.alert.AlertKind;

public interface ShipRightShipStatusRepository extends JpaRepository<ShipRightShipStatus, Long> {

    Optional<ShipRightShipStatus> findByShipIdAndCurrentTrue(Long shipId);

    /** The current rows of every vessel: the Vetting list (active vessels) and the Vessels list (all). */
    @Query("select s from ShipRightShipStatus s join fetch s.ship where s.current = true")
    List<ShipRightShipStatus> findAllCurrent();

    /** The vessel's latest row the office entered: the five subscores the API does not give come from it. */
    Optional<ShipRightShipStatus> findFirstByShipIdAndSourceOrderByUpdatedAtDescIdDesc(Long shipId,
                                                                                        RightShipSource source);

    /**
     * The sidebar counter of Vetting: active vessels whose inspection is expired or valid until "until" at most. Ids,
     * so VettingService counts each vessel once together with its SIRE 2.0 items (P41). P43: not the vessels whose
     * VETTING_INSPECTION snooze holds, at the inspection's level today (0 expired, 1 within 30 days, else 2;
     * AlertLevels).
     */
    @Query("""
            select s.ship.id from ShipRightShipStatus s
            where s.current = true and s.ship.active = true and s.inspectionValidUntil <= :until
              and not exists (select a.id from AlertSnooze a
                              where a.kind = :kind and a.subjectId = s.ship.id and a.endedAt is null and a.until >= :today
                                and a.level <= case when s.inspectionValidUntil < :today then 0
                                                    when s.inspectionValidUntil <= :d30 then 1 else 2 end)""")
    List<Long> findShipIdsInspectionDueUntil(LocalDate until, LocalDate today, LocalDate d30, AlertKind kind);

    boolean existsByShipId(Long shipId);
}
