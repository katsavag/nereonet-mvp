package com.nereonix.nereonet_mvp.ship;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.nereonix.nereonet_mvp.core.vesseldata.VesselLookup;

/**
 * The VesselAPI lookups waiting for Save (P45), in memory: the form carries only a random token (lookupToken); the
 * values taken on Save come from here, never from the browser. A token works only for the user who looked up, for an
 * hour; the controller also checks the lookup's IMO number against the form's. A restart forgets them all (the user
 * looks up again).
 */
@Component
public class VesselLookupStore {

    static final Duration TTL = Duration.ofHours(1);
    static final int MAX_ENTRIES = 1_000;

    private record Entry(Long userId, VesselLookup lookup, Instant storedAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock = Clock.systemUTC();

    /** Keeps the lookup for this user; returns its token. */
    public String put(Long userId, VesselLookup lookup) {
        Instant now = clock.instant();
        entries.values().removeIf(entry -> entry.storedAt().plus(TTL).isBefore(now));
        if (entries.size() >= MAX_ENTRIES) {
            entries.entrySet().stream()
                    .min(Map.Entry.comparingByValue((a, b) -> a.storedAt().compareTo(b.storedAt())))
                    .ifPresent(oldest -> entries.remove(oldest.getKey()));
        }
        String token = UUID.randomUUID().toString();
        entries.put(token, new Entry(userId, lookup, now));
        return token;
    }

    /** The lookup of this token, if it is this user's and not expired. */
    public Optional<VesselLookup> get(String token, Long userId) {
        if (token == null || token.isBlank() || userId == null) {
            return Optional.empty();
        }
        Entry entry = entries.get(token.strip());
        if (entry == null || !entry.userId().equals(userId)
                || entry.storedAt().plus(TTL).isBefore(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(entry.lookup());
    }

    /** Used: forgotten. */
    public void remove(String token) {
        if (token != null) {
            entries.remove(token.strip());
        }
    }
}
