package com.nereonix.nereonet_mvp.core.vesseldata;

/**
 * The three VesselAPI calls (P45), each a GET of its own, kept as one VesselDataSnapshot row per vessel.
 *   PARTICULARS  /vessel/{imo}?filter.idType=imo           name, type, flag, dimensions, tonnage, engine, statistics
 *   POSITION     /vessel/{imo}/position?filter.idType=imo  the last AIS position (looks back at most 80 hours)
 *   ETA          /vessel/{imo}/eta?filter.idType=imo       destination, ETA and draught declared by the vessel
 */
public enum VesselEndpoint {

    PARTICULARS(""),
    POSITION("/position"),
    ETA("/eta");

    private final String suffix;

    VesselEndpoint(String suffix) {
        this.suffix = suffix;
    }

    /** The path below the base URL, e.g. "/vessel/9328716/position?filter.idType=imo". imo: 7 digits, validated. */
    public String path(String imo) {
        return "/vessel/" + imo + suffix + "?filter.idType=imo";
    }

    /** The key of the endpoint in templates and messages: particulars, position, eta. */
    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
