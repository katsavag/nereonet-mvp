package com.nereonix.nereonet_mvp.core.vetting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

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
 * A vessel's RightShip standing (P40). Every change (the office's edit, the nightly sync, "Refresh from RightShip")
 * adds a row and makes it the current one; the earlier rows stay as history. At most one row per vessel is current.
 * <ul>
 *   <li>safetyScore: null not rated, 0 sanctioned ("No safety score"), else 1 to 5; indicative: RightShip marks it
 *   indicative; calculatedAt: the date RightShip calculated it.</li>
 *   <li>Six subscores, each 1 to 5 or null (not known), in three impact groups (Subscore): vessel incident and DOC
 *   (highest), PSC deficiencies and detentions (medium), flag and class (lowest). The API gives only the DOC subscore;
 *   on an API row the other five are kept from the last manual entry, entered from the report of
 *   subscoresReportDate.</li>
 *   <li>GHG Rating: a letter (GhgRating; U not rated), verified, plus (A+ only), ratedAt.</li>
 *   <li>Inspection: required, the last outcome, valid until.</li>
 *   <li>restricted and its comments; platformLink: the vessel's page on the RightShip platform.</li>
 *   <li>source MANUAL or API; updatedBy (null for the sync, or once that user is deleted) and updatedAt (UTC).</li>
 * </ul>
 */
@Entity
@Table(indexes = @Index(name = "ix_right_ship_status_ship", columnList = "ship_id, is_current"))
@Getter
@Setter
public class ShipRightShipStatus {

    public static final int COMMENTS_MAX_LENGTH = 2000;
    public static final int LINK_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Ship ship;

    // "current" alone is a keyword in some SQL dialects.
    @Column(name = "is_current", nullable = false)
    private boolean current;

    // Safety Score
    private Integer safetyScore;

    @Column(nullable = false)
    private boolean indicative;

    private LocalDate calculatedAt;

    // Subscores (Subscore), 1 to 5 or null.
    private Integer vesselIncident;
    private Integer doc;
    private Integer pscDeficiencies;
    private Integer detentions;
    private Integer flag;
    private Integer classPerformance;

    // API rows only: the date of the report the five subscores the API does not give were entered from.
    private LocalDate subscoresReportDate;

    // GHG Rating
    @Enumerated(EnumType.STRING)
    @Column(length = 1)
    private GhgRating ghgRating;

    @Column(nullable = false)
    private boolean ghgVerified;

    @Column(nullable = false)
    private boolean ghgPlus;

    private LocalDate ghgRatedAt;

    // RightShip Inspection
    private Boolean inspectionRequired;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private InspectionOutcome lastInspectionOutcome;

    private LocalDate inspectionValidUntil;

    // Restrictions
    @Column(nullable = false)
    private boolean restricted;

    @Column(length = COMMENTS_MAX_LENGTH)
    private String restrictionComments;

    @Column(length = LINK_MAX_LENGTH)
    private String platformLink;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RightShipSource source;

    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User updatedBy;

    @Column(nullable = false)
    private Instant updatedAt;

    /** The value of one subscore. */
    public Integer subscore(Subscore subscore) {
        return switch (subscore) {
            case VESSEL_INCIDENT -> vesselIncident;
            case DOC -> doc;
            case PSC_DEFICIENCIES -> pscDeficiencies;
            case DETENTIONS -> detentions;
            case FLAG -> flag;
            case CLASS -> classPerformance;
        };
    }

    public void setSubscore(Subscore subscore, Integer value) {
        switch (subscore) {
            case VESSEL_INCIDENT -> vesselIncident = value;
            case DOC -> doc = value;
            case PSC_DEFICIENCIES -> pscDeficiencies = value;
            case DETENTIONS -> detentions = value;
            case FLAG -> flag = value;
            case CLASS -> classPerformance = value;
        }
    }

    public InspectionState inspectionState(LocalDate today) {
        return InspectionState.of(lastInspectionOutcome, inspectionValidUntil, inspectionRequired, today);
    }

    /** A copy of every RightShip value, not current, without id, source, updatedBy and updatedAt: a new row. */
    public ShipRightShipStatus copy() {
        ShipRightShipStatus copy = new ShipRightShipStatus();
        copy.ship = ship;
        copy.safetyScore = safetyScore;
        copy.indicative = indicative;
        copy.calculatedAt = calculatedAt;
        for (Subscore s : Subscore.values()) {
            copy.setSubscore(s, subscore(s));
        }
        copy.subscoresReportDate = subscoresReportDate;
        copy.ghgRating = ghgRating;
        copy.ghgVerified = ghgVerified;
        copy.ghgPlus = ghgPlus;
        copy.ghgRatedAt = ghgRatedAt;
        copy.inspectionRequired = inspectionRequired;
        copy.lastInspectionOutcome = lastInspectionOutcome;
        copy.inspectionValidUntil = inspectionValidUntil;
        copy.restricted = restricted;
        copy.restrictionComments = restrictionComments;
        copy.platformLink = platformLink;
        return copy;
    }

    /** The same RightShip values as other (not id, current, source, updatedBy, updatedAt). */
    public boolean sameValues(ShipRightShipStatus other) {
        return other != null && values().equals(other.values());
    }

    private List<Object> values() {
        return Arrays.asList(safetyScore, indicative, calculatedAt, vesselIncident, doc, pscDeficiencies, detentions,
                flag, classPerformance, subscoresReportDate, ghgRating, ghgVerified, ghgPlus, ghgRatedAt,
                inspectionRequired, lastInspectionOutcome, inspectionValidUntil, restricted,
                Objects.toString(restrictionComments, ""), Objects.toString(platformLink, ""));
    }
}
