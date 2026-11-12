package com.nereonix.nereonet_mvp.core.notification;

import java.time.Instant;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

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

// An in-app message for one user, shown on /notifications. It stays unread until the user opens it or marks it as
// read (P23). Deleted together with the user (database cascade).
@Entity
@Getter
@Setter
public class Notification {

    public static final int TEXT_MAX_LENGTH = 500;
    public static final int SUBJECT_MAX_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    // Null on notifications saved before P23 (they show the bell icon).
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private NotificationType type;

    // The ship or person it concerns: "MV Demo", "Jane Doe · MV Demo". Null on notifications saved before P23.
    @Column(length = SUBJECT_MAX_LENGTH)
    private String subject;

    @Column(nullable = false, length = TEXT_MAX_LENGTH)
    private String text;

    // An app path such as /ship, or null.
    private String link;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // Null while unread.
    private Instant readAt;
}
