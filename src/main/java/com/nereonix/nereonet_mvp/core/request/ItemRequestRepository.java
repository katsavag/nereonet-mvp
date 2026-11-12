package com.nereonix.nereonet_mvp.core.request;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;


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

    // "Export all not exported" (P21): every approved request never exported, of one ship or (shipId null) all.
    @Query("""
            select r from ItemRequest r
            where r.status = :status and r.exportedAt is null
              and (:shipId is null or r.ship.id = :shipId)
            order by r.decidedAt desc, r.id desc""")
    List<ItemRequest> findNotExported(RequestStatus status, Long shipId);

    // One ship's requests with this status: the ship page's Requests tab; and those never exported, its Overview tab.
    long countByStatusAndShipId(RequestStatus status, Long shipId);

    long countByStatusAndShipIdAndExportedAtIsNull(RequestStatus status, Long shipId);

    // Requests with this status never exported, every ship: the sidebar counter of Requests.
    long countByStatusAndExportedAtIsNull(RequestStatus status);

    // Whether there is any request with this status, every ship: the empty state of Requests (P25).
    boolean existsByStatus(RequestStatus status);

    // The Master's approvals (P33): one ship's waiting requests, oldest first, and its decided ones, newest decision
    // first. The detail page finds a request only within the Master's ship, so another ship's is a 404.
    List<ItemRequest> findAllByShipIdAndStatusOrderByCreatedAtAscIdAsc(Long shipId, RequestStatus status);

    List<ItemRequest> findAllByShipIdAndStatusInOrderByDecidedAtDescIdDesc(Long shipId, Collection<RequestStatus> statuses);

    Optional<ItemRequest> findByIdAndShipId(Long id, Long shipId);

    // The Master's sidebar counter of Approvals: requests with this status on that user's ship, in one query.
    @Query("""
            select count(r) from ItemRequest r
            where r.status = :status
              and r.ship = (select u.ship from User u where u.email = :email)""")
    long countByStatusOnShipOf(RequestStatus status, String email);
}
