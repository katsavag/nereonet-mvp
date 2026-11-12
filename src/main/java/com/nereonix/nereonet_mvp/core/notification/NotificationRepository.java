package com.nereonix.nereonet_mvp.core.notification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    long countByUserEmailAndReadAtIsNull(String email);

    List<Notification> findAllByUserEmailOrderByCreatedAtDescIdDesc(String email);

    /** One of this user's notifications; empty for someone else's. */
    Optional<Notification> findByIdAndUserEmail(Long id, String email);

    // A bulk update: entities already loaded in this transaction keep readAt null. Returns how many it marked.
    @Modifying
    @Query("update Notification n set n.readAt = :now where n.user.email = :email and n.readAt is null")
    int markAllRead(String email, Instant now);
}
