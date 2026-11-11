package com.nereonix.nereonet_mvp.core.certificate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

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

// A crew member's certificate, e.g. "STCW Basic Safety". Deleted together with the user (database cascade).
@Entity
@Getter
@Setter
public class Certificate {

    public static final int TYPE_MAX_LENGTH = 100;
    public static final int NUMBER_MAX_LENGTH = 100;

    // Reminders are sent this many days before expiry, smallest first.
    public static final List<Integer> REMINDER_DAYS = List.of(30, 60, 90);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(nullable = false, length = TYPE_MAX_LENGTH)
    private String type;

    // Null when not set.
    @Column(length = NUMBER_MAX_LENGTH)
    private String number;

    // A calendar date, no time zone. "today" is the UTC date.
    @Column(nullable = false)
    private LocalDate expiresOn;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public long daysLeft(LocalDate today) {
        return ChronoUnit.DAYS.between(today, expiresOn);
    }

    public boolean isExpired(LocalDate today) {
        return expiresOn.isBefore(today);
    }

    /** The smallest of 30, 60, 90 with daysLeft <= it, or null if more than 90 days are left. */
    public Integer reminderThreshold(LocalDate today) {
        long daysLeft = daysLeft(today);
        return REMINDER_DAYS.stream().filter(days -> daysLeft <= days).findFirst().orElse(null);
    }
}
