package com.nereonix.nereonet_mvp.core.request;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;


public interface ItemRequestRepository extends JpaRepository<ItemRequest, Long> {

    // One ship's requests with this status (the dev data seeds PENDING ones only while there are none).
    long countByStatusAndShipId(RequestStatus status, Long shipId);

    // The Master's approvals: one ship's pending requests, oldest first, and its approved or declined ones,
    // newest decision first. The detail page finds a request only within the Master's ship, so another ship's is a 404.
    List<ItemRequest> findAllByShipIdAndStatusOrderByCreatedAtAscIdAsc(Long shipId, RequestStatus status);

    List<ItemRequest> findAllByShipIdAndStatusOrderByDecidedAtDescIdDesc(Long shipId, RequestStatus status);

    Optional<ItemRequest> findByIdAndShipId(Long id, Long shipId);

    // Approving or declining: the row is locked until the decision is saved, so a second click, or a cancel at
    // the same moment, waits and then sees the request is no longer PENDING.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ItemRequest r where r.id = :id and r.ship.id = :shipId")
    Optional<ItemRequest> findForDecision(Long id, Long shipId);

    // The requests of the person who asked: their list, newest first, and one of them (anyone else's is a 404).
    List<ItemRequest> findAllByRequestedByIdOrderByCreatedAtDescIdDesc(Long userId);

    Optional<ItemRequest> findByIdAndRequestedById(Long id, Long userId);

    // The Chief cook's and crew's sidebar counter of Requests: their own requests with this status, one query.
    long countByRequestedByEmailAndStatus(String email, RequestStatus status);

    // The Master's sidebar counter of Approvals: requests with this status on that user's ship, in one query.
    @Query("""
            select count(r) from ItemRequest r
            where r.status = :status
              and r.ship = (select u.ship from User u where u.email = :email)""")
    long countByStatusOnShipOf(RequestStatus status, String email);
}
