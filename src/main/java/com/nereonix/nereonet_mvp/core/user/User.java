package com.nereonix.nereonet_mvp.core.user;

import java.time.Instant;

import com.nereonix.nereonet_mvp.core.ship.Ship;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "users") // "user" is a reserved word in PostgreSQL
@Getter
@Setter
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Always stored lowercase (see UserService).
    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    // Null for OFFICE_USER, required for every other role.
    @ManyToOne
    private Ship ship;

    // Ship roles only. Null for OFFICE_USER. Required in every form since P18 (it sets the role), but users from
    // before may have none (see rankNeedsReview).
    @Enumerated(EnumType.STRING)
    private Rank rank;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private Instant createdAt;

    /**
     * P18: a crew member whose role is not the one their rank sets (Rank.role()), or who has no rank. They keep their
     * role until the office saves them with a rank. Always false for an office user.
     */
    public boolean rankNeedsReview() {
        return role != Role.OFFICE_USER && (rank == null || rank.role() != role);
    }
}
