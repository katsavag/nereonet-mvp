package com.nereonix.nereonet_mvp.core.alert;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.User;

/**
 * Snoozing alerts (P43): an office user puts a certificate or vetting item aside for a while, with a reason; every
 * office user can undo it. While a snooze holds the item is left out of the counters, the Overview and the
 * reminders (the callers ask {@link #holds}); it stays in its list, muted. A snooze ends by itself (sync) when its
 * date passes, when the item is resolved (it no longer exists) or when it gets worse (its level dropped,
 * AlertLevels). Snooze, undo and every automatic end are in the audit log.
 */
@Service
public class SnoozeService {

    /** An expired item can be snoozed for at most this many days. */
    public static final int EXPIRED_MAX_DAYS = 30;

    private final AlertSnoozeRepository repository;
    private final AuditService auditService;

    public SnoozeService(AlertSnoozeRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    public static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    /** The snoozes not ended whose date has not passed, by their item. One query. */
    @Transactional(readOnly = true)
    public Map<AlertKey, AlertSnooze> active() {
        LocalDate today = today();
        Map<AlertKey, AlertSnooze> active = new LinkedHashMap<>();
        for (AlertSnooze s : repository.findAllActive()) {
            if (!s.getUntil().isBefore(today)) {
                active.putIfAbsent(s.key(), s);
            }
        }
        return active;
    }

    /** The snooze that holds for this alert at its current level, or null: an active one whose item has not got worse. */
    public static AlertSnooze holding(Map<AlertKey, AlertSnooze> active, Alert alert) {
        AlertSnooze s = active.get(alert.key());
        return s != null && holds(s, alert.level()) ? s : null;
    }

    /** The snooze holds while the item is not worse than when it was snoozed. */
    public static boolean holds(AlertSnooze snooze, int currentLevel) {
        return !AlertLevels.worse(currentLevel, snooze.getLevel());
    }

    /**
     * Snoozes one alert until "until": a new row at the item's current level, recorded as "Alert snoozed". ship: the
     * vessel it concerns; title: the item in words for the audit log ("Certificate STCW Basic Safety Training of
     * jane@example.com"). An alert already snoozed gets its snooze replaced (the old one ends as undone).
     */
    @Transactional
    public AlertSnooze snooze(Alert alert, Ship ship, String title, SnoozeReason reason, String note, LocalDate until,
                              User by) {
        Instant now = Instant.now();
        AlertSnooze existing = repository.findAllActive().stream().filter(s -> s.key().equals(alert.key())).findFirst()
                .orElse(null);
        if (existing != null) {
            existing.end(SnoozeEndReason.UNDONE, now);
        }
        AlertSnooze snooze = new AlertSnooze();
        snooze.setKind(alert.key().kind());
        snooze.setSubjectId(alert.key().subjectId());
        snooze.setShip(ship);
        snooze.setSnoozedBy(by);
        snooze.setReason(reason);
        snooze.setNote(note == null || note.isBlank() ? null : note.strip());
        snooze.setUntil(until);
        snooze.setLevel(alert.level());
        snooze.setCreatedAt(now);
        repository.save(snooze);
        auditService.record("ALERT_SNOOZED", ship, title + ": until " + until + ", " + describe(reason, snooze.getNote()), by);
        return snooze;
    }

    /** Undoes a snooze: it ends now as undone, recorded as "Alert snooze undone". 404 when there is no such active snooze. */
    @Transactional
    public AlertSnooze undo(Long id, String title, User by) {
        AlertSnooze snooze = repository.findByIdAndEndedAtIsNull(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        snooze.end(SnoozeEndReason.UNDONE, Instant.now());
        auditService.record("ALERT_SNOOZE_UNDONE", snooze.getShip(), title, by);
        return snooze;
    }

    /** The active snooze with this id, or 404. */
    @Transactional(readOnly = true)
    public AlertSnooze get(Long id) {
        return repository.findByIdAndEndedAtIsNull(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * Ends the snoozes that no longer apply, given every alert that exists right now (the Overview's items before
     * snoozes are applied): the date passed, the item is gone (resolved), or it got worse. Each end is recorded as
     * "Alert snooze ended" by the system, with its reason. Returns the snoozes still holding, by item.
     */
    @Transactional
    public Map<AlertKey, AlertSnooze> sync(Collection<Alert> current, LocalDate today) {
        Map<AlertKey, Integer> levels = new HashMap<>();
        current.forEach(alert -> levels.put(alert.key(), alert.level()));
        Instant now = Instant.now();
        Map<AlertKey, AlertSnooze> holding = new LinkedHashMap<>();
        for (AlertSnooze s : repository.findAllActive()) {
            Integer level = levels.get(s.key());
            SnoozeEndReason end = null;
            if (s.getUntil().isBefore(today)) {
                end = SnoozeEndReason.DATE_PASSED;
            } else if (level == null) {
                end = SnoozeEndReason.RESOLVED;
            } else if (AlertLevels.worse(level, s.getLevel())) {
                end = SnoozeEndReason.WORSENED;
            }
            if (end == null) {
                holding.putIfAbsent(s.key(), s);
                continue;
            }
            s.end(end, now);
            auditService.record("ALERT_SNOOZE_ENDED", s.getShip(),
                    s.getKind().name() + " " + s.getSubjectId() + ": " + end.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                            + (end == SnoozeEndReason.WORSENED ? " (level " + s.getLevel() + " to " + level + ")" : ""), null);
        }
        return holding;
    }

    /** The items of a list whose snooze holds, from the alerts of the list: for the "Snoozed" filter. */
    public static <T> List<T> snoozed(Map<AlertKey, AlertSnooze> active, Collection<T> items,
                                      java.util.function.Function<T, Alert> alertOf) {
        return items.stream().filter(item -> holding(active, alertOf.apply(item)) != null).toList();
    }

    private static String describe(SnoozeReason reason, String note) {
        String text = reason.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return note == null ? text : text + " (" + note + ")";
    }
}
