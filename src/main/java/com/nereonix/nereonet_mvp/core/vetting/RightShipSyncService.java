package com.nereonix.nereonet_mvp.core.vetting;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.vetting.RightShipClient.RightShipException;
import com.nereonix.nereonet_mvp.core.vetting.RightShipClient.VesselInfo;

/**
 * The RightShip API sync (P40), read only, and only when the API is configured (RightShipSettings); otherwise nothing
 * here ever calls RightShip.
 * <ul>
 *   <li>Nightly (app.rightship.sync-cron, UTC): GET /vessel/v2/updates?SinceDate= the day of the last run that worked,
 *   then POST /vessel/v2/info for the changed IMO numbers of the active vessels. On the first run, or when the last
 *   run that worked is more than 7 days ago, every active vessel instead.</li>
 *   <li>"Refresh from RightShip" on a vessel's RightShip page: POST /vessel/v2/info for that vessel.</li>
 * </ul>
 * A failure is logged and saved (RightShipSync), never thrown: the pages show "Last sync failed at ... UTC" until a
 * run works again. The HTTP calls are made outside any transaction.
 */
@Service
public class RightShipSyncService {

    private static final Logger log = LoggerFactory.getLogger(RightShipSyncService.class);

    /** SinceDate goes back at most this far; a longer gap asks for every vessel. */
    static final Duration MAX_GAP = Duration.ofDays(7);
    /** IMO numbers per POST /vessel/v2/info. */
    static final int BATCH = 100;

    /** What a run did: the vessels asked about and the ones whose data changed; failed: it stopped on an error. */
    public record Result(boolean ran, boolean failed, int asked, int updated) {
    }

    /** What "Refresh from RightShip" did. */
    public enum Refresh {
        UPDATED, UNCHANGED, NOT_FOUND, FAILED, NOT_CONFIGURED
    }

    private final RightShipSettings settings;
    private final RightShipClient client;
    private final ShipRepository shipRepository;
    private final RightShipSyncRepository syncRepository;
    private final VettingService vettingService;
    private final TransactionTemplate transaction;
    private final Clock clock = Clock.systemUTC();

    public RightShipSyncService(RightShipSettings settings, RightShipClient client, ShipRepository shipRepository,
                                RightShipSyncRepository syncRepository, VettingService vettingService,
                                PlatformTransactionManager transactionManager) {
        this.settings = settings;
        this.client = client;
        this.shipRepository = shipRepository;
        this.syncRepository = syncRepository;
        this.vettingService = vettingService;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Scheduled(cron = "${app.rightship.sync-cron:0 30 2 * * *}", zone = "UTC")
    public void nightly() {
        Result result = run();
        if (result.ran()) {
            log.info("RightShip sync: {} vessels asked, {} updated{}", result.asked(), result.updated(),
                    result.failed() ? ", failed" : "");
        }
    }

    /** One run of the nightly sync (see the class). Nothing at all when the API is not configured. */
    public Result run() {
        if (!settings.configured()) {
            return new Result(false, false, 0, 0);
        }
        Instant started = clock.instant();
        try {
            RightShipSync state = transaction.execute(status -> syncRepository.findById(RightShipSync.ID)
                    .orElseGet(RightShipSync::new));
            Map<String, Ship> active = transaction.execute(status -> shipRepository.findAllByActiveTrueOrderByNameAsc()
                    .stream().collect(Collectors.toMap(Ship::getImoNumber, Function.identity(), (a, b) -> a,
                            LinkedHashMap::new)));
            Set<String> wanted;
            Instant lastSuccess = state.getLastSuccessAt();
            if (lastSuccess == null || lastSuccess.isBefore(started.minus(MAX_GAP))) {
                wanted = new LinkedHashSet<>(active.keySet());
            } else {
                wanted = new LinkedHashSet<>();
                for (String imo : client.updatedSince(LocalDate.ofInstant(lastSuccess, ZoneOffset.UTC))) {
                    if (active.containsKey(imo)) {
                        wanted.add(imo);
                    }
                }
            }
            int updated = 0;
            List<String> imos = new ArrayList<>(wanted);
            for (int from = 0; from < imos.size(); from += BATCH) {
                List<VesselInfo> infos = client.info(imos.subList(from, Math.min(from + BATCH, imos.size())));
                for (VesselInfo info : infos) {
                    Ship ship = active.get(info.imoNumber());
                    if (ship != null && Boolean.TRUE.equals(transaction.execute(status ->
                            vettingService.applyApi(ship, info, null, "RIGHTSHIP_SYNCED")))) {
                        updated++;
                    }
                }
            }
            transaction.executeWithoutResult(status -> {
                RightShipSync sync = syncRepository.findById(RightShipSync.ID).orElseGet(RightShipSync::new);
                sync.setLastSuccessAt(started);
                syncRepository.save(sync);
            });
            return new Result(true, false, wanted.size(), updated);
        } catch (RuntimeException e) {
            failed(e);
            return new Result(true, true, 0, 0);
        }
    }

    /** "Refresh from RightShip" for one vessel by user: its data from the API now. */
    public Refresh refresh(Ship ship, User user) {
        if (!settings.configured()) {
            return Refresh.NOT_CONFIGURED;
        }
        try {
            VesselInfo info = client.info(List.of(ship.getImoNumber())).stream()
                    .filter(v -> ship.getImoNumber().equals(v.imoNumber())).findFirst().orElse(null);
            if (info == null) {
                return Refresh.NOT_FOUND;
            }
            boolean changed = Boolean.TRUE.equals(transaction.execute(status ->
                    vettingService.applyApi(ship, info, user, "RIGHTSHIP_REFRESHED")));
            return changed ? Refresh.UPDATED : Refresh.UNCHANGED;
        } catch (RuntimeException e) {
            failed(e);
            return Refresh.FAILED;
        }
    }

    // Logged and saved, never thrown. The message of a RightShipException never holds the credentials.
    private void failed(RuntimeException e) {
        String error = e instanceof RightShipException ? e.getMessage() : e.getClass().getSimpleName();
        log.warn("RightShip sync failed: {}", error);
        try {
            transaction.executeWithoutResult(status -> {
                RightShipSync sync = syncRepository.findById(RightShipSync.ID).orElseGet(RightShipSync::new);
                sync.setLastFailureAt(clock.instant());
                sync.setLastError(error == null ? null
                        : error.substring(0, Math.min(error.length(), RightShipSync.ERROR_MAX_LENGTH)));
                syncRepository.save(sync);
            });
        } catch (RuntimeException again) {
            log.warn("Could not save the RightShip sync failure: {}", again.getMessage());
        }
    }
}
