package com.nereonix.nereonet_mvp.core.request;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.nereonix.nereonet_mvp.core.ship.ShipCount;

public interface ItemRequestRepository extends JpaRepository<ItemRequest, Long> {

    /** Requests with this status, newest decision first. shipId null means every ship. */
    @Query("""
            select r from ItemRequest r
            where r.status = :status
              and (:shipId is null or r.ship.id = :shipId)
              and (:notExported = false or r.exportedAt is null)
            order by r.decidedAt desc, r.id desc""")
    List<ItemRequest> findDecided(RequestStatus status, Long shipId, boolean notExported);

    Optional<ItemRequest> findByIdAndStatus(Long id, RequestStatus status);

    List<ItemRequest> findAllByIdInAndStatusOrderByDecidedAtDescIdDesc(Collection<Long> ids, RequestStatus status);

    // One ship's requests with this status: the ship page's Requests tab; and those never exported, its Overview tab.
    long countByStatusAndShipId(RequestStatus status, Long shipId);

    long countByStatusAndShipIdAndExportedAtIsNull(RequestStatus status, Long shipId);

    // Requests with this status never exported, every ship: the sidebar counter of Requests.
    long countByStatusAndExportedAtIsNull(RequestStatus status);

    // Requests with this status never exported, per ship.
    @Query("""
            select new com.nereonix.nereonet_mvp.core.ship.ShipCount(r.ship.id, count(r))
            from ItemRequest r where r.status = :status and r.exportedAt is null group by r.ship.id""")
    List<ShipCount> countNotExportedByShip(RequestStatus status);
}
