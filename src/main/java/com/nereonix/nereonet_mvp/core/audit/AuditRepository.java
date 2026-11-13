package com.nereonix.nereonet_mvp.core.audit;

import java.util.List;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

// Not a JpaRepository on purpose: audit entries can be added and read, never updated or deleted.
// The one exception is detachActor, needed to delete a user.
// The audit log page reads its entries page by page with core/list/ListQueries (audit/AuditLogService, P19).
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

    // Whether this was ever done: the starter template of required documents is added once (P42).
    boolean existsByAction(String action);
}
