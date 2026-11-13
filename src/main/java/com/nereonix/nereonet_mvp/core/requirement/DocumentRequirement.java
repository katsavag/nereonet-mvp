package com.nereonix.nereonet_mvp.core.requirement;

import java.util.EnumSet;
import java.util.Set;

import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.ship.ShipType;
import com.nereonix.nereonet_mvp.core.user.Rank;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import lombok.Getter;
import lombok.Setter;

/**
 * A required document (P42): one row of the office's grid on one vessel type. A certificate type (the same type list
 * and spelling rules as certificates, P20: two types are one when equal after strip() and toLowerCase), the ranks it
 * applies to, the vessel type (null: all vessels), whether it is only optional (shown, never counted) and an optional
 * note ("needed before joining"). At most one row per type and vessel type (RequirementService keeps it so).
 * suggestion: a row of the starter template the office has not saved yet. Nothing derived is stored: who is missing
 * what is worked out on every read (Compliance).
 */
@Entity
@Getter
@Setter
public class DocumentRequirement {

    public static final int NOTE_MAX_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = Certificate.TYPE_MAX_LENGTH)
    private String certificateType;

    // Null: every vessel type, and vessels without a type.
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ShipType shipType;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "document_requirement_rank", joinColumns = @JoinColumn(name = "requirement_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "rank", nullable = false, length = 40)
    private Set<Rank> ranks = EnumSet.noneOf(Rank.class);

    @Column(nullable = false)
    private boolean optional;

    @Column(length = NOTE_MAX_LENGTH)
    private String note;

    @Column(nullable = false)
    private boolean suggestion;

    public boolean appliesTo(Rank rank) {
        return rank != null && ranks.contains(rank);
    }
}
