package com.nereonix.nereonet_mvp.core.alert;

import java.time.Instant;
import java.time.LocalDate;

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
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * An alert the office put aside for a while (P43): the kind and subject id identify the item (AlertKey; nothing else
 * of it is stored, it is worked out again on every read), the vessel it concerns, who snoozed it, why (a reason and,
 * for Other, a note), until when, and once it ended, when and why (SnoozeEndReason). level is what the snooze
 * compares against to end by itself when the item gets worse (AlertLevels): the item's level when it was snoozed.
 * While it holds (endedAt null, until not passed, the item not worse) the item is left out of the sidebar counters,
 * the Overview and the certificate reminders; it stays in its list, muted, with Undo.
 */
@Entity
@Table(indexes = @Index(name = "ix_alert_snooze_item", columnList = "kind, subject_id, ended_at"))
@Getter
@Setter
public class AlertSnooze {

    public static final int NOTE_MAX_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AlertKind kind;

    @Column(nullable = false)
    private long subjectId;

    // The vessel the item concerns (its row in the lists); null only when it had none.
    @ManyToOne
    private Ship ship;

    // Null once that user is deleted.
    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User snoozedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SnoozeReason reason;

    @Column(length = NOTE_MAX_LENGTH)
    private String note;

    // The last day the snooze holds (UTC).
    @Column(nullable = false)
    private LocalDate until;

    @Column(nullable = false)
    private int level;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant endedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SnoozeEndReason endReason;

    public AlertKey key() {
        return new AlertKey(kind, subjectId);
    }

    public boolean active() {
        return endedAt == null;
    }

    /** Ends the snooze now for this reason; nothing happens when it had ended. */
    public void end(SnoozeEndReason why, Instant now) {
        if (endedAt == null) {
            endedAt = now;
            endReason = why;
        }
    }
}
