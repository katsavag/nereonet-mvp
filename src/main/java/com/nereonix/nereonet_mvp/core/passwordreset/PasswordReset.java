package com.nereonix.nereonet_mvp.core.passwordreset;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One password reset link sent by email (P14). It works once (usedAt) and until expiresAt (60 minutes). The rows are
 * kept after use: they count towards the limit of 5 links per address per hour.
 * userId is a plain column, not a foreign key, so deleting a user is not blocked; a link whose user is gone, or is
 * another user now, does not work (PasswordResetService.findValid).
 */
@Entity
@Table(indexes = @Index(columnList = "email, createdAt"))
@Getter
@Setter
public class PasswordReset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The JWT's jti.
    @Column(nullable = false, unique = true)
    private UUID tokenId;

    @Column(nullable = false)
    private Long userId;

    // Lowercase, as users are stored.
    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    // Set when the link was used, or when another link of the same user was used: then it stops working.
    private Instant usedAt;
}
