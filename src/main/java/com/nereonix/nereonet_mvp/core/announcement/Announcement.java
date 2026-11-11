package com.nereonix.nereonet_mvp.core.announcement;

import java.time.Instant;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

// A message from the office to the crew of one ship or of the whole fleet.
// Never edited or deleted after it is sent: it is the proof of what the crew confirmed.
@Entity
@Getter
@Setter
public class Announcement {

    public static final int TITLE_MAX_LENGTH = 200;
    public static final int BODY_MAX_LENGTH = 5000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = TITLE_MAX_LENGTH)
    private String title;

    // Plain text.
    @Column(nullable = false, updatable = false, length = BODY_MAX_LENGTH)
    private String body;

    // Null means the whole fleet.
    @ManyToOne
    private Ship ship;

    // Null when the office user who sent it was deleted (the database sets it to null).
    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User createdBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public boolean isFleet() {
        return ship == null;
    }
}
