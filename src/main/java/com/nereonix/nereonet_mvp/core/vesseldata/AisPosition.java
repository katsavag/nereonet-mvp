package com.nereonix.nereonet_mvp.core.vesseldata;

import java.time.Instant;

import tools.jackson.databind.JsonNode;

/**
 * The display fields of GET /vessel/{imo}/position (P45): {"vesselPosition": {...}, "positionCheck": {...}}. Null
 * where VesselAPI gave no value. reportedAt is when the vessel sent the AIS message (UTC).
 * possibleError: VesselAPI flags the position as doubtful (suspected_glitch, a speed from the previous report above
 * the vessel's typical speed, or an identity that does not match its record).
 */
public record AisPosition(Double latitude, Double longitude, Instant reportedAt, Double speedOverGround,
                          Double courseOverGround, Long heading, Long navigationStatus, boolean possibleError) {

    /** From a body; null when it is not JSON. */
    public static AisPosition parse(String rawJson) {
        JsonNode root = VesselJson.parse(rawJson);
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode p = VesselJson.object(root, "vesselPosition");
        JsonNode check = root.get("positionCheck");
        boolean doubtful = VesselJson.isTrue(p, "suspected_glitch")
                || VesselJson.isTrue(check, "exceeds_typical_speed")
                || VesselJson.isFalse(check, "identity_match");
        return new AisPosition(VesselJson.number(p, "latitude"), VesselJson.number(p, "longitude"),
                VesselJson.instant(VesselJson.text(p, "timestamp")),
                VesselJson.number(p, "sog"), VesselJson.number(p, "cog"), VesselJson.integer(p, "heading"),
                VesselJson.integer(p, "nav_status"), doubtful);
    }

    /** Both coordinates are given. */
    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }

    public Double absLatitude() {
        return latitude == null ? null : Math.abs(latitude);
    }

    /** N or S (message vessel.hemisphere.N). */
    public String latitudeHemisphere() {
        return latitude != null && latitude < 0 ? "S" : "N";
    }

    public Double absLongitude() {
        return longitude == null ? null : Math.abs(longitude);
    }

    /** E or W. */
    public String longitudeHemisphere() {
        return longitude != null && longitude < 0 ? "W" : "E";
    }

    /** An AIS navigation status 0 to 15 (message key vessel.navStatus.<n>); else the number as given. */
    public boolean knownNavigationStatus() {
        return navigationStatus != null && navigationStatus >= 0 && navigationStatus <= 15;
    }
}
