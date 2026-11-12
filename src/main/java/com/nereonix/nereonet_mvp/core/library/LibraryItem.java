package com.nereonix.nereonet_mvp.core.library;

import java.time.Instant;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

// A document in a ship's library. Its content lives in versions; currentVersion is the one the crew see.
// Never deleted: a retired item is hidden from the crew but keeps its versions and acknowledgements.
@Entity
@Getter
@Setter
public class LibraryItem {

    public static final int TITLE_MAX_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(updatable = false)
    private Ship ship;

    @Column(nullable = false, length = TITLE_MAX_LENGTH)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LibraryCategory category;

    // The crew must acknowledge the current version.
    @Column(nullable = false)
    private boolean required;

    @Column(nullable = false)
    private boolean retired = false;

    private Instant retiredAt;

    // Null when not retired, or when that user was deleted.
    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User retiredBy;

    // Null only while the item is being created, before its first version is saved.
    @ManyToOne
    private LibraryItemVersion currentVersion;

    // Null when that user was deleted.
    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User createdBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // When "Send reminder" last notified the crew who have not acknowledged (P22); null if never. At most once per
    // LibraryService.REMINDER_INTERVAL.
    private Instant lastReminderAt;
}
