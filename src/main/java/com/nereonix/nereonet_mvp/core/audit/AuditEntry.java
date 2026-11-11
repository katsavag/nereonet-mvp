package com.nereonix.nereonet_mvp.core.audit;

import java.time.Instant;

import org.hibernate.annotations.Immutable;

import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;

// Read-only: written once by AuditService, never changed or deleted. No setters on purpose.
@Entity
@Immutable
@Getter
public class AuditEntry {

    public static final int DETAILS_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant occurredAt;

    // Null for system jobs, and for users that were deleted (then actorDeleted is true).
    @ManyToOne
    private User actor;

    // The default lets Hibernate add the column to a table that already has rows.
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean actorDeleted;

    // A code such as SHIP_CREATED.
    @Column(nullable = false, length = 50)
    private String action;

    @ManyToOne
    private Ship ship;

    // Short plain text. Never passwords, tokens or invitation links.
    @Column(length = DETAILS_MAX_LENGTH)
    private String details;

    protected AuditEntry() {
    }

    AuditEntry(Instant occurredAt, User actor, String action, Ship ship, String details) {
        this.occurredAt = occurredAt;
        this.actor = actor;
        this.action = action;
        this.ship = ship;
        this.details = details;
    }
}
