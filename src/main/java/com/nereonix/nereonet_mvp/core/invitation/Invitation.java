package com.nereonix.nereonet_mvp.core.invitation;

import java.time.Instant;
import java.util.UUID;

import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class Invitation {

    public enum Status { PENDING, ACCEPTED, CANCELLED }

    // What the office sees: a PENDING invitation past expiresAt is EXPIRED. Computed, never stored.
    public enum DisplayStatus { PENDING, EXPIRED, ACCEPTED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Always stored lowercase (see InvitationService).
    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    // Null for OFFICE_USER, required for every other role.
    @ManyToOne
    private Ship ship;

    // Ship roles only; copied to the user on registration.
    @Enumerated(EnumType.STRING)
    private Rank rank;

    // The JWT's jti. Deleting the invitation makes its link stop working.
    @Column(nullable = false, unique = true)
    private UUID tokenId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private Instant expiresAt;

    // Null when the office user who sent it was deleted.
    @ManyToOne
    private User invitedBy;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant acceptedAt;

    public DisplayStatus getDisplayStatus() {
        if (status == Status.PENDING) {
            return expiresAt.isBefore(Instant.now()) ? DisplayStatus.EXPIRED : DisplayStatus.PENDING;
        }
        return DisplayStatus.valueOf(status.name());
    }
}
