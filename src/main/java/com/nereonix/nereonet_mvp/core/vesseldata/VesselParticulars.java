package com.nereonix.nereonet_mvp.core.vesseldata;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * The display fields of GET /vessel/{imo} (P45), read from the body ({"vessel": {...}}). Null where VesselAPI gave no
 * value. The speeds and draughts are VesselAPI's statistics over about 31 days, not the latest AIS values.
 */
public record VesselParticulars(Long imo, Long mmsi, String name, String nameAis, String callSign,
                                String vesselType, String vesselSubtype, String operatingStatus,
                                String country, String countryCode, String homePort, List<FormerName> formerNames,
                                Long yearBuilt, Double length, String lengthUnit, Double breadth,
                                String breadthUnit, Long grossTonnage, Long deadweightTonnage, String engineType,
                                String engineModel, Long kilowattPower, Double speedAverage, Double speedMaximum,
                                Double draughtAverage, Double draughtMaximum) {

    /** A former name and the year it was used until (text, as given; may be null). */
    public record FormerName(String name, String yearUntil) {
    }

    /** From a body; null when it is not JSON. */
    public static VesselParticulars parse(String rawJson) {
        JsonNode root = VesselJson.parse(rawJson);
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode v = VesselJson.object(root, "vessel");
        // Identical entries (the same name and year) once, in the order given.
        Set<FormerName> former = new LinkedHashSet<>();
        JsonNode names = v.get("former_names");
        if (names != null && names.isArray()) {
            for (JsonNode entry : names) {
                String name = VesselJson.text(entry, "name");
                if (name != null) {
                    former.add(new FormerName(name, VesselJson.text(entry, "year_until")));
                }
            }
        }
        return new VesselParticulars(
                VesselJson.integer(v, "imo"), VesselJson.integer(v, "mmsi"),
                VesselJson.text(v, "name"), VesselJson.text(v, "name_ais"), VesselJson.text(v, "call_sign"),
                VesselJson.text(v, "vessel_type"), VesselJson.text(v, "vessel_subtype"),
                VesselJson.text(v, "operating_status"),
                VesselJson.text(v, "country"), VesselJson.text(v, "country_code"), VesselJson.text(v, "home_port"),
                new ArrayList<>(former),
                VesselJson.integer(v, "year_built"),
                VesselJson.number(v, "length"), VesselJson.text(v, "length_unit"),
                VesselJson.number(v, "breadth"), VesselJson.text(v, "breadth_unit"),
                VesselJson.integer(v, "gross_tonnage"), VesselJson.integer(v, "deadweight_tonnage"),
                VesselJson.text(v, "engine_type"), VesselJson.text(v, "engine_model_name"),
                VesselJson.integer(v, "kilowatt_power"),
                VesselJson.number(v, "speed_calculated_avg"), VesselJson.number(v, "speed_observed_max"),
                VesselJson.number(v, "draught_calculated_avg"), VesselJson.number(v, "draught_observed_max"));
    }

    /** "Bulk Carrier", or "Cargo · Bulk Carrier" when both are given and differ; null when neither is. */
    public String typeText() {
        if (vesselType != null && vesselSubtype != null && !vesselType.equalsIgnoreCase(vesselSubtype)) {
            return vesselType + " · " + vesselSubtype;
        }
        return vesselSubtype != null ? vesselSubtype : vesselType;
    }

    /** Any of the statistics is given. */
    public boolean hasStatistics() {
        return speedAverage != null || speedMaximum != null || draughtAverage != null || draughtMaximum != null;
    }
}
