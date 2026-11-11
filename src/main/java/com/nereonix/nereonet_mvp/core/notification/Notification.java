package com.nereonix.nereonet_mvp.core.notification;

import java.time.Instant;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.nereonix.nereonet_mvp.core.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

// An in-app message for one user, shown on /notifications. Deleted together with the user (database cascade).
@Entity
@Getter
@Setter
public class Notification {

    public static final int TEXT_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(nullable = false, length = TEXT_MAX_LENGTH)
    private String text;

    // An app path such as /ship, or null.
    private String link;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // Null while unread.
    private Instant readAt;
}
