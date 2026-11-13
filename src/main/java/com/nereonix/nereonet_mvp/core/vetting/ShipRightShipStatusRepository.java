package com.nereonix.nereonet_mvp.core.vetting;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ShipRightShipStatusRepository extends JpaRepository<ShipRightShipStatus, Long> {

    Optional<ShipRightShipStatus> findByShipIdAndCurrentTrue(Long shipId);

    /** The current rows of every vessel: the Vetting list (active vessels) and the Vessels list (all). */
    @Query("select s from ShipRightShipStatus s join fetch s.ship where s.current = true")
    List<ShipRightShipStatus> findAllCurrent();

    /** The vessel's latest row the office entered: the five subscores the API does not give come from it. */
    Optional<ShipRightShipStatus> findFirstByShipIdAndSourceOrderByUpdatedAtDescIdDesc(Long shipId,
                                                                                        RightShipSource source);

    /** The sidebar counter of Vetting: active vessels whose inspection is expired or valid until "until" at most. */
    @Query("""
            select count(s) from ShipRightShipStatus s
            where s.current = true and s.ship.active = true and s.inspectionValidUntil <= :until""")
    long countInspectionDueUntil(LocalDate until);

    boolean existsByShipId(Long shipId);
}
