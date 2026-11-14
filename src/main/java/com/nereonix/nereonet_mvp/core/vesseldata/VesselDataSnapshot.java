package com.nereonix.nereonet_mvp.core.vesseldata;

import java.time.Instant;

import com.nereonix.nereonet_mvp.core.ship.Ship;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * What VesselAPI last returned for one vessel and one call (P45): one row per vessel and VesselEndpoint, written by
 * VesselDataService on a lookup that was saved or a refresh, never on opening a page. rawJson is the body exactly as
 * received, unknown fields included; the pages parse their fields from it on read (VesselJson). A call that fails
 * (any result but OK) changes only lastAttemptAt and lastAttemptResult: the previous body and dates stay.
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"ship_id", "endpoint"}))
@Getter
@Setter
public class VesselDataSnapshot {

    /** The largest body kept, in characters; a larger answer counts as PROVIDER_ERROR. */
    public static final int RAW_JSON_MAX_LENGTH = 100_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Ship ship;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VesselEndpoint endpoint;

    // The body of the last call that worked; null until one did.
    @Column(length = RAW_JSON_MAX_LENGTH)
    private String rawJson;

    // The time the provider gives for the data in rawJson (the AIS report's timestamp); null for the particulars.
    private Instant providerReportedAt;

    // When the call that returned rawJson was made.
    private Instant lastSuccessAt;

    @Column(nullable = false)
    private Instant lastAttemptAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VesselApiResult lastAttemptResult;
}
