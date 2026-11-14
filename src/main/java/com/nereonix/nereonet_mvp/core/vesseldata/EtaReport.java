package com.nereonix.nereonet_mvp.core.vesseldata;

import java.time.Instant;

import tools.jackson.databind.JsonNode;

/**
 * The display fields of GET /vessel/{imo}/eta (P45): {"vesselEta": {...}}. Destination, ETA and draught are what the
 * crew entered in the AIS set, as received. eta: parsed as UTC when it is a date and time, else etaText holds it as
 * given. reportedAt: when VesselAPI received the report (UTC).
 */
public record EtaReport(String destination, String destinationPort, Instant eta, String etaText, Double draught,
                        Instant reportedAt) {

    /** From a body; null when it is not JSON. */
    public static EtaReport parse(String rawJson) {
        JsonNode root = VesselJson.parse(rawJson);
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode e = VesselJson.object(root, "vesselEta");
        String etaText = VesselJson.text(e, "eta");
        return new EtaReport(VesselJson.text(e, "destination"), VesselJson.text(e, "destination_port"),
                VesselJson.instant(etaText), etaText, VesselJson.number(e, "draught"),
                VesselJson.instant(VesselJson.text(e, "timestamp")));
    }
}
