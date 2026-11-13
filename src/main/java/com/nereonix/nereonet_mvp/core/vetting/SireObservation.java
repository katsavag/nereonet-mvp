package com.nereonix.nereonet_mvp.core.vetting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

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

/**
 * A negative observation of a SIRE 2.0 inspection (P41): where in the VIQ (chapter 1 to 12 and the question number),
 * its type and text as the report gives them, and how the office follows it up: the operator's comment, the
 * corrective action, the office user who owns it, the target date and, once done, the closed date.
 */
@Entity
@Getter
@Setter
public class SireObservation {

    /** The chapters of the SIRE 2.0 question library (names: sire.chapter.N). */
    public static final List<Integer> CHAPTERS = IntStream.rangeClosed(1, 12).boxed().toList();

    public static final int QUESTION_MAX_LENGTH = 20;
    public static final int DESCRIPTION_MAX_LENGTH = 1000;
    public static final int TEXT_MAX_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private SireInspection inspection;

    @Column(nullable = false)
    private int chapter;

    // "5.4.1"
    @Column(nullable = false, length = QUESTION_MAX_LENGTH)
    private String questionNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ObservationType type;

    @Column(nullable = false, length = DESCRIPTION_MAX_LENGTH)
    private String description;

    @Column(length = TEXT_MAX_LENGTH)
    private String operatorComment;

    @Column(length = TEXT_MAX_LENGTH)
    private String correctiveAction;

    // An office user; null while nobody owns it, or once that user is deleted.
    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User owner;

    private LocalDate targetDate;

    private LocalDate closedOn;

    @Column(nullable = false)
    private Instant createdAt;

    /** Closed once it has a closed date; overdue while open after its target date; else open. */
    public ObservationStatus status(LocalDate today) {
        if (closedOn != null) {
            return ObservationStatus.CLOSED;
        }
        return targetDate != null && targetDate.isBefore(today) ? ObservationStatus.OVERDUE : ObservationStatus.OPEN;
    }
}
