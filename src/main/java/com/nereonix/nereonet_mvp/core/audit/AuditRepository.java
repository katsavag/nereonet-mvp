package com.nereonix.nereonet_mvp.core.audit;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

// Not a JpaRepository on purpose: audit entries can be added and read, never updated or deleted.
// The one exception is detachActor, needed to delete a user.
public interface AuditRepository extends Repository<AuditEntry, Long> {

    AuditEntry save(AuditEntry entry);

    // Keeps the user's entries but removes the link to them; the log then shows "Deleted user".
    // Native SQL because AuditEntry is @Immutable. Clears the persistence context so no loaded entry
    // still points at the user.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update audit_entry set actor_id = null, actor_deleted = true where actor_id = :userId",
            nativeQuery = true)
    void detachActor(Long userId);

    List<AuditEntry> findAll();

    // Newest first. A null shipId means all ships; from is inclusive, to is exclusive.
    // from and to must not be null: PostgreSQL cannot type a "? is null" timestamp parameter.
    @EntityGraph(attributePaths = {"actor", "ship"})
    @Query("""
            select a from AuditEntry a
            where (:shipId is null or a.ship.id = :shipId)
              and a.occurredAt >= :from
              and a.occurredAt < :to
            order by a.occurredAt desc, a.id desc""")
    Page<AuditEntry> search(Long shipId, Instant from, Instant to, Pageable pageable);
}
