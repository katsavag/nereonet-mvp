package com.nereonix.nereonet_mvp.core.announcement;

import java.time.Instant;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.User;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

// One crew member who has to acknowledge an announcement. Saved when the announcement is created,
// never added later. Deleted together with the user (database cascade).
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"announcement_id", "user_id"}))
@Getter
@Setter
public class AnnouncementRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(updatable = false)
    private Announcement announcement;

    @ManyToOne(optional = false)
    @JoinColumn(updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    // The user's ship when the announcement was created.
    @ManyToOne(optional = false)
    @JoinColumn(updatable = false)
    private Ship ship;

    // Null until the crew member acknowledges it.
    private Instant acknowledgedAt;
}
