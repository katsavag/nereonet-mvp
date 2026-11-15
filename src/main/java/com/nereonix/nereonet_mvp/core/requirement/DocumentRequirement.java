package com.nereonix.nereonet_mvp.core.requirement;

import java.util.EnumSet;
import java.util.Set;

import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.ship.Ship;
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
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

/**
 * A required document: one row of the office's grid. A certificate type (the same type list and spelling rules
 * as certificates, P20: two types are one when equal after strip() and toLowerCase), the ranks it applies to, whether
 * it is only optional (shown, never counted) and an optional note ("needed before joining").
 * Each row belongs to one vessel (ship). A vessel starts with the defaults of its type (StarterRequirements),
 * copied when it is created; changes on its grid apply to it alone. At most one row per type and vessel
 * (RequirementService keeps it so).
 * shipType: only on rows from before P58, when requirements were per vessel type (null: all vessels) and ship was
 * null; VesselRequirementsMigration copies them to every vessel they applied to and deletes them on startup.
 * suggestion: a row copied from the defaults that the office has not saved yet. Nothing derived is stored: who is
 * missing what is worked out on every read (Compliance).
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

    // The vessel this requirement is for. Null only on a row from before P58 (see shipType), until migrated.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ship_id")
    private Ship ship;

    // Before P58 only: the vessel type of the row (null: every vessel type, and vessels without a type). Not set on
    // vessel rows; read once by VesselRequirementsMigration.
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

    /** Whether this is the requirement of that vessel (by id; the ship is not loaded). */
    public boolean isFor(Ship vessel) {
        return ship != null && vessel != null && ship.getId() != null && ship.getId().equals(vessel.getId());
    }
}
