package com.nereonix.nereonet_mvp.core.vesseldata;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.nereonix.nereonet_mvp.core.vesseldata.VesselApiClient.Fetch;

/**
 * What the three VesselAPI calls returned for one IMO number at fetchedAt (P45): "Look up by IMO" on the Add vessel
 * form and "Refresh from VesselAPI" on Edit vessel. Kept by the server for the form (ship/VesselLookupStore) until
 * Save; never sent to the browser as such.
 */
public record VesselLookup(String imo, Instant fetchedAt, Map<VesselEndpoint, Fetch> fetches) {

    public VesselLookup {
        Map<VesselEndpoint, Fetch> all = new EnumMap<>(VesselEndpoint.class);
        for (VesselEndpoint endpoint : VesselEndpoint.values()) {
            Fetch fetch = fetches.get(endpoint);
            all.put(endpoint, fetch == null ? Fetch.failed(VesselApiResult.PROVIDER_ERROR) : fetch);
        }
        fetches = Map.copyOf(all);
    }

    public Fetch fetch(VesselEndpoint endpoint) {
        return fetches.get(endpoint);
    }

    public VesselApiResult result(VesselEndpoint endpoint) {
        return fetch(endpoint).result();
    }

    public VesselApiResult particularsResult() {
        return result(VesselEndpoint.PARTICULARS);
    }

    public VesselApiResult positionResult() {
        return result(VesselEndpoint.POSITION);
    }

    public VesselApiResult etaResult() {
        return result(VesselEndpoint.ETA);
    }

    /** Null unless the particulars call worked. */
    public VesselParticulars particulars() {
        Fetch fetch = fetch(VesselEndpoint.PARTICULARS);
        return fetch.ok() ? VesselParticulars.parse(fetch.rawJson()) : null;
    }

    /** Null unless the position call worked. */
    public AisPosition position() {
        Fetch fetch = fetch(VesselEndpoint.POSITION);
        return fetch.ok() ? AisPosition.parse(fetch.rawJson()) : null;
    }

    /** Null unless the ETA call worked. */
    public EtaReport eta() {
        Fetch fetch = fetch(VesselEndpoint.ETA);
        return fetch.ok() ? EtaReport.parse(fetch.rawJson()) : null;
    }

    /** For the audit log: "particulars OK, position NOT_FOUND, eta OK". */
    public String results() {
        return Stream.of(VesselEndpoint.values())
                .map(endpoint -> endpoint.key() + " " + result(endpoint))
                .collect(Collectors.joining(", "));
    }
}
