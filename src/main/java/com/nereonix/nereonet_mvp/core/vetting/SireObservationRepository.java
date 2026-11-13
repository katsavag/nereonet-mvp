package com.nereonix.nereonet_mvp.core.vetting;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.nereonix.nereonet_mvp.core.alert.AlertKind;

public interface SireObservationRepository extends JpaRepository<SireObservation, Long> {

    @Query("select o from SireObservation o left join fetch o.owner where o.inspection.id = :inspectionId")
    List<SireObservation> findAllByInspectionId(Long inspectionId);

    @Query("select o from SireObservation o where o.inspection.id in :inspectionIds")
    List<SireObservation> findAllByInspectionIdIn(Collection<Long> inspectionIds);

    Optional<SireObservation> findByIdAndInspectionId(Long id, Long inspectionId);

    /** The open observations past their target date on the latest inspection of an active vessel (the Overview). */
    @Query("select o from SireObservation o join fetch o.inspection i join fetch i.ship s where s.active = true and"
            + SireInspectionRepository.LATEST + "and o.closedOn is null and o.targetDate < :today")
    List<SireObservation> findOverdueOnLatest(LocalDate today);

    /** The vessels of findOverdueOnLatest, for the sidebar counter, less the observations whose snooze holds (P43). */
    @Query("select distinct i.ship.id from SireObservation o join o.inspection i where i.ship.active = true and"
            + SireInspectionRepository.LATEST + "and o.closedOn is null and o.targetDate < :today"
            + " and not exists (select a.id from AlertSnooze a where a.kind = :kind and a.subjectId = o.id"
            + "   and a.endedAt is null and a.until >= :today)")
    List<Long> findShipIdsWithOverdueOnLatest(LocalDate today, AlertKind kind);
}
