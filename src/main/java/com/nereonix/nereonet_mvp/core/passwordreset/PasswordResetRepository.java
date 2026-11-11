package com.nereonix.nereonet_mvp.core.passwordreset;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordResetRepository extends JpaRepository<PasswordReset, Long> {

    Optional<PasswordReset> findByTokenId(UUID tokenId);

    // Links sent to this address since a moment: the limit of 5 per hour.
    long countByEmailAndCreatedAtAfter(String email, Instant since);

    // The user's links that still work, to stop them once one was used.
    List<PasswordReset> findAllByUserIdAndUsedAtIsNull(Long userId);
}
