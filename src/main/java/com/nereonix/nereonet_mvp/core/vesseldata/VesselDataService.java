package com.nereonix.nereonet_mvp.core.vesseldata;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.vesseldata.VesselApiClient.Fetch;

import jakarta.annotation.PreDestroy;

/**
 * VesselAPI data per vessel (P45). The provider is called only by lookup(), on a user's "Look up by IMO" or "Refresh
 * from VesselAPI", from the server, never while a page opens; the pages read the stored rows (view()).
 * <ul>
 *   <li>lookup: the three calls at the same time, each on its own, so one failing or slow call never stops the others.
 *   Made outside any transaction.</li>
 *   <li>store: one VesselDataSnapshot row per call; a call that failed keeps the previous body and dates and changes
 *   only the last attempt.</li>
 * </ul>
 * Audit entries (never the key): VESSEL_LOOKUP (Add vessel, no vessel yet), VESSEL_DATA_REFRESHED (Edit vessel) with
 * the IMO number and each call's result, and VESSEL_DATA_ACCEPTED with the fields taken from VesselAPI.
 */
@Service
public class VesselDataService {

    // Above the client's 5 s connect + 5 s read, so the client's own timeout normally decides.
    private static final long WAIT_SECONDS = 15;

    private final VesselApiSettings settings;
    private final VesselApiClient client;
    private final VesselDataSnapshotRepository snapshotRepository;
    private final AuditService auditService;
    private final Clock clock = Clock.systemUTC();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public VesselDataService(VesselApiSettings settings, VesselApiClient client,
                             VesselDataSnapshotRepository snapshotRepository, AuditService auditService) {
        this.settings = settings;
        this.client = client;
        this.snapshotRepository = snapshotRepository;
        this.auditService = auditService;
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    /** "Look up by IMO" and "Refresh from VesselAPI" are offered. */
    public boolean configured() {
        return settings.configured();
    }

    /** The three calls for imo (7 digits, validated), at the same time. Throws when VesselAPI is not configured. */
    public VesselLookup lookup(String imo) {
        if (!configured()) {
            throw new IllegalStateException("VesselAPI is not configured");
        }
        Instant fetchedAt = clock.instant();
        Map<VesselEndpoint, Future<Fetch>> calls = new EnumMap<>(VesselEndpoint.class);
        for (VesselEndpoint endpoint : VesselEndpoint.values()) {
            calls.put(endpoint, executor.submit(() -> client.fetch(endpoint, imo)));
        }
        Map<VesselEndpoint, Fetch> fetches = new EnumMap<>(VesselEndpoint.class);
        for (Map.Entry<VesselEndpoint, Future<Fetch>> call : calls.entrySet()) {
            fetches.put(call.getKey(), await(call.getValue()));
        }
        return new VesselLookup(imo, fetchedAt, fetches);
    }

    private static Fetch await(Future<Fetch> call) {
        try {
            Fetch fetch = call.get(WAIT_SECONDS, TimeUnit.SECONDS);
            return fetch == null ? Fetch.failed(VesselApiResult.PROVIDER_ERROR) : fetch;
        } catch (TimeoutException e) {
            call.cancel(true);
            return Fetch.failed(VesselApiResult.TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Fetch.failed(VesselApiResult.PROVIDER_ERROR);
        } catch (ExecutionException e) {
            return Fetch.failed(VesselApiResult.PROVIDER_ERROR);
        }
    }

    /** "Look up by IMO" on the Add vessel form: recorded with each call's result; nothing is stored yet. */
    @Transactional
    public void recordLookup(VesselLookup lookup) {
        auditService.record("VESSEL_LOOKUP", null, "IMO " + lookup.imo() + ": " + lookup.results());
    }

    /** "Refresh from VesselAPI" on Edit vessel: the rows are updated now (the vessel's own fields only on Save). */
    @Transactional
    public void recordRefresh(Ship ship, VesselLookup lookup) {
        store(ship, lookup);
        auditService.record("VESSEL_DATA_REFRESHED", ship, "IMO " + lookup.imo() + ": " + lookup.results());
    }

    /**
     * A vessel saved with a lookup: its rows (Add vessel) and the fields taken from VesselAPI. fields: e.g.
     * "name, flag"; empty when none was taken. storeRows: false when the rows were stored on refresh already.
     */
    @Transactional
    public void recordAccepted(Ship ship, VesselLookup lookup, String fields, boolean storeRows) {
        if (!ship.getImoNumber().equals(lookup.imo())) {
            throw new IllegalArgumentException("The lookup is for another IMO number");
        }
        if (storeRows) {
            store(ship, lookup);
        }
        auditService.record("VESSEL_DATA_ACCEPTED", ship, "IMO " + lookup.imo() + ", from VesselAPI: "
                + (fields.isEmpty() ? "no fields" : fields) + "; " + lookup.results());
    }

    // One row per call: a call that worked replaces the body and dates, any other only the last attempt.
    private void store(Ship ship, VesselLookup lookup) {
        for (VesselEndpoint endpoint : VesselEndpoint.values()) {
            Fetch fetch = lookup.fetch(endpoint);
            VesselDataSnapshot row = snapshotRepository.findByShipAndEndpoint(ship, endpoint).orElseGet(() -> {
                VesselDataSnapshot created = new VesselDataSnapshot();
                created.setShip(ship);
                created.setEndpoint(endpoint);
                return created;
            });
            row.setLastAttemptAt(lookup.fetchedAt());
            row.setLastAttemptResult(fetch.result());
            if (fetch.ok()) {
                row.setRawJson(fetch.rawJson());
                row.setLastSuccessAt(lookup.fetchedAt());
                row.setProviderReportedAt(switch (endpoint) {
                    case PARTICULARS -> null;
                    case POSITION -> AisPosition.parse(fetch.rawJson()).reportedAt();
                    case ETA -> EtaReport.parse(fetch.rawJson()).reportedAt();
                });
            }
            snapshotRepository.save(row);
        }
    }

    /** The stored data of a vessel; no call to VesselAPI. */
    @Transactional(readOnly = true)
    public VesselDataView view(Ship ship) {
        return new VesselDataView(snapshotRepository.findByShip(ship));
    }
}
