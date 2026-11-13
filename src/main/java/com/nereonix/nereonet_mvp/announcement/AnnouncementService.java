package com.nereonix.nereonet_mvp.announcement;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.nereonix.nereonet_mvp.core.announcement.Announcement;
import com.nereonix.nereonet_mvp.core.announcement.AnnouncementRecipient;
import com.nereonix.nereonet_mvp.core.announcement.AnnouncementRecipientRepository;
import com.nereonix.nereonet_mvp.core.announcement.AnnouncementRepository;
import com.nereonix.nereonet_mvp.core.announcement.RecipientCount;
import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.list.ListQueries;
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.notification.NotificationService;
import com.nereonix.nereonet_mvp.core.notification.NotificationType;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;

// Announcements sent by the office. There is no edit or delete: an announcement is the proof of what
// the crew confirmed. Each recipient reads and acknowledges their own (P27: forUser, recipient, acknowledge).
@Service
public class AnnouncementService {

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementRecipientRepository recipientRepository;
    private final ShipRepository shipRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ListQueries listQueries;
    private final NotificationService notificationService;
    private final MessageSource messageSource;

    public AnnouncementService(AnnouncementRepository announcementRepository,
                               AnnouncementRecipientRepository recipientRepository, ShipRepository shipRepository,
                               UserRepository userRepository, AuditService auditService, ListQueries listQueries,
                               NotificationService notificationService, MessageSource messageSource) {
        this.announcementRepository = announcementRepository;
        this.recipientRepository = recipientRepository;
        this.shipRepository = shipRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.listQueries = listQueries;
        this.notificationService = notificationService;
        this.messageSource = messageSource;
    }

    /** An announcement with its recipient counts, for the list. */
    public record Row(Announcement announcement, long recipients, long acknowledged) {
    }

    /** The recipients of one announcement: acknowledged (earliest first) and not yet (by name). */
    public record Recipients(List<AnnouncementRecipient> acknowledged, List<AnnouncementRecipient> pending) {
    }

    /**
     * One page of the Announcements list (P19), paginated in the database, then the recipient counts of that page's
     * announcements only. With a shipId, only the ones sent to all ships or with that ship among their targets (P28).
     * The search is the title. Sort keys: sentAt (default, newest first), title, target (All ships first, then the
     * ones to one ship by its name, then those to several ships, fewest first). The target ships of the page are read
     * with one more query (Announcement.targetShips is read in batches).
     */
    @Transactional(readOnly = true)
    public Page<Row> findAll(Long shipId, ListQuery list) {
        Page<Announcement> page = listQueries.page(Announcement.class, list, (root, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (shipId != null) {
                // At most one joined row per announcement: the target with this id, if it is one.
                Join<Announcement, Ship> target = root.join("targetShips", JoinType.LEFT);
                target.on(cb.equal(target.get("id"), shipId));
                where.add(cb.or(cb.isTrue(root.get("fleetWide")), cb.isNotNull(target.get("id"))));
            }
            if (list.getContains() != null) {
                where.add(ListQueries.contains(cb, root.get("title"), list.getContains()));
            }
            return where;
        }, (root, cb) -> {
            Expression<Instant> sentAt = root.get("createdAt");
            return switch (list.getSort()) {
                case "title" -> List.of(cb.lower(root.get("title")), sentAt);
                case "target" -> {
                    // Joined only when it is the one target, so an announcement to several ships stays one row.
                    Expression<Integer> targets = cb.size(root.<Set<Ship>>get("targetShips"));
                    Join<Announcement, Ship> only = root.join("targetShips", JoinType.LEFT);
                    only.on(cb.equal(targets, 1));
                    Expression<Integer> kind = cb.<Integer>selectCase()
                            .when(cb.isTrue(root.get("fleetWide")), 0)
                            .when(cb.isNotNull(only.get("id")), 1)
                            .otherwise(2);
                    yield List.of(kind, cb.lower(only.get("name")), targets, sentAt);
                }
                default -> List.of(sentAt);
            };
        }, null);
        Map<Long, RecipientCount> counts = page.isEmpty() ? Map.of()
                : recipientRepository.countFor(page.map(Announcement::getId).getContent()).stream()
                        .collect(Collectors.toMap(RecipientCount::announcementId, Function.identity()));
        return page.map(a -> {
            RecipientCount count = counts.get(a.getId());
            return count == null ? new Row(a, 0, 0) : new Row(a, count.recipients(), count.acknowledged());
        });
    }

    @Transactional(readOnly = true)
    public List<Ship> findAllShips() {
        return shipRepository.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public List<Ship> findActiveShips() {
        return shipRepository.findAllByOrderByNameAsc().stream().filter(Ship::isActive).toList();
    }

    // 404 if there is no such announcement.
    @Transactional(readOnly = true)
    public Announcement get(Long id) {
        return announcementRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * The recipients of one announcement, both lists grouped by ship (by ship name): acknowledged earliest first and
     * not yet by name within a ship.
     */
    @Transactional(readOnly = true)
    public Recipients findRecipients(Announcement announcement) {
        List<AnnouncementRecipient> all = recipientRepository.findAllByAnnouncementOrderByUserFullNameAsc(announcement);
        Comparator<AnnouncementRecipient> byShip = Comparator.comparing((AnnouncementRecipient r) -> r.getShip().getName(),
                String.CASE_INSENSITIVE_ORDER).thenComparing(r -> r.getShip().getId());
        return new Recipients(
                all.stream().filter(r -> r.getAcknowledgedAt() != null)
                        .sorted(byShip.thenComparing(AnnouncementRecipient::getAcknowledgedAt)).toList(),
                all.stream().filter(r -> r.getAcknowledgedAt() == null).sorted(byShip).toList());
    }

    /** The acknowledgements of one ship's recipients (the detail page, P28). */
    public record ShipAcknowledgements(Ship ship, int recipients, int acknowledged) {
    }

    /**
     * The acknowledgements per ship, by ship name: every target ship, also one that had nobody on board, and every
     * ship the recipients were on (for All ships, the active ships when it was sent that had crew).
     */
    public List<ShipAcknowledgements> acknowledgementsPerShip(Announcement announcement, Recipients recipients) {
        Map<Long, Ship> ships = new LinkedHashMap<>();
        Map<Long, int[]> counts = new LinkedHashMap<>();
        announcement.getTargetShips().forEach(s -> ships.put(s.getId(), s));
        for (AnnouncementRecipient r : recipients.acknowledged()) {
            ships.putIfAbsent(r.getShip().getId(), r.getShip());
            counts.computeIfAbsent(r.getShip().getId(), id -> new int[2])[1]++;
        }
        for (AnnouncementRecipient r : recipients.pending()) {
            ships.putIfAbsent(r.getShip().getId(), r.getShip());
            counts.computeIfAbsent(r.getShip().getId(), id -> new int[2])[0]++;
        }
        return ships.values().stream()
                .sorted(Comparator.comparing(Ship::getName, String.CASE_INSENSITIVE_ORDER).thenComparing(Ship::getId))
                .map(s -> {
                    int[] c = counts.getOrDefault(s.getId(), new int[2]);
                    return new ShipAcknowledgements(s, c[0] + c[1], c[1]);
                })
                .toList();
    }

    /** How many people on one ship an announcement would go to (the review step, P10). */
    public record ShipRecipients(Ship ship, int people) {
    }

    /** A sent announcement, the number of people it went to (counted when it was sent) and on how many ships. */
    public record Sent(Announcement announcement, int recipients, int ships) {
    }

    /**
     * Where an announcement would go if it were sent now (P28). ships: the active ships, by name (every active ship
     * for the fleet; the chosen ones that are still active otherwise). leftOut: chosen ships that are no longer active,
     * by name; a chosen id with no ship at all is simply left out.
     */
    public record Targets(boolean fleet, List<Ship> ships, List<Ship> leftOut) {

        public boolean isEmpty() {
            return ships.isEmpty();
        }

        public List<Long> shipIds() {
            return ships.stream().map(Ship::getId).toList();
        }
    }

    /** The targets of an announcement to the whole fleet (shipIds ignored) or to these ships. */
    @Transactional(readOnly = true)
    public Targets targets(boolean fleet, Collection<Long> shipIds) {
        if (fleet) {
            return new Targets(true, findActiveShips(), List.of());
        }
        Comparator<Ship> byName = Comparator.comparing(Ship::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Ship::getId);
        List<Ship> chosen = shipRepository.findAllById(shipIds).stream().sorted(byName).toList();
        return new Targets(false, chosen.stream().filter(Ship::isActive).toList(),
                chosen.stream().filter(s -> !s.isActive()).toList());
    }

    /**
     * Who an announcement to these targets would go to if it were sent now, per ship by name: the same rule as
     * create(). Every target ship is listed, also one with nobody on board.
     */
    @Transactional(readOnly = true)
    public List<ShipRecipients> recipientsPerShip(Targets targets) {
        Map<Long, Long> perShip = recipients(targets).stream()
                .collect(Collectors.groupingBy(user -> user.getShip().getId(), Collectors.counting()));
        return targets.ships().stream()
                .map(s -> new ShipRecipients(s, perShip.getOrDefault(s.getId(), 0L).intValue()))
                .toList();
    }

    /**
     * Sends an announcement to the whole fleet (fleet true, shipIds ignored) or to the chosen ships that are still
     * active (P28): the others are left out, and with none left it is a 400 (the controller checks first). The
     * recipients are fixed now: every enabled user with a ship role who is on one of those ships (on any active ship
     * for the fleet), counted again here, whatever the review page showed. Each recipient gets an in-app notification,
     * "Announcement · <title>", that opens the announcement (P27). The audit entry lists the target ship ids.
     */
    @Transactional
    public Sent create(String title, String body, boolean fleet, Collection<Long> shipIds, String actorEmail) {
        Targets targets = targets(fleet, shipIds);
        if (!fleet && targets.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No active ship chosen");
        }

        Announcement announcement = new Announcement();
        announcement.setTitle(title.strip());
        announcement.setBody(body.strip());
        announcement.setFleetWide(fleet);
        if (!fleet) {
            announcement.getTargetShips().addAll(targets.ships());
        }
        announcement.setCreatedBy(userRepository.findByEmail(actorEmail).orElseThrow());
        announcement.setCreatedAt(Instant.now());
        announcementRepository.save(announcement);

        List<User> users = recipients(targets);
        for (User user : users) {
            AnnouncementRecipient recipient = new AnnouncementRecipient();
            recipient.setAnnouncement(announcement);
            recipient.setUser(user);
            recipient.setShip(user.getShip());
            recipientRepository.save(recipient);
        }
        // Crew screens, and so their notifications, are English only.
        String text = messageSource.getMessage("announcement.notification", new Object[] {announcement.getTitle()},
                Locale.ENGLISH);
        for (User user : users) {
            notificationService.notify(user, NotificationType.ANNOUNCEMENT, user.getShip().getName(), text,
                    "/ship/news/" + announcement.getId());
        }

        // The ship of the entry (the audit log's ship filter) only when there is exactly one.
        Ship auditShip = !fleet && targets.ships().size() == 1 ? targets.ships().getFirst() : null;
        String target = fleet ? "all vessels"
                : "vessel ids " + targets.ships().stream().map(s -> s.getId().toString()).collect(Collectors.joining(", "))
                        + ": " + announcement.getTargetNames();
        auditService.record("ANNOUNCEMENT_CREATED", auditShip,
                announcement.getTitle() + " (" + target + "; " + users.size() + " recipients)");
        return new Sent(announcement, users.size(), targets.ships().size());
    }

    /**
     * The announcements this ship user is a recipient of, each with their acknowledgement (P27): still to acknowledge
     * first (P37), each group newest first.
     */
    @Transactional(readOnly = true)
    public List<AnnouncementRecipient> forUser(String email) {
        return recipientRepository.findAllForUser(email);
    }

    /**
     * This user's place among the recipients of an announcement: the announcement and their acknowledgement. 404 when
     * there is no such announcement or they are not a recipient, so nobody learns of announcements not meant for them.
     */
    @Transactional(readOnly = true)
    public AnnouncementRecipient recipient(String email, Long announcementId) {
        return recipientRepository.findByAnnouncementIdAndUserEmail(announcementId, email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * This user opens a news item (P39): their place among its recipients, read from now on. The first time, it
     * records when they read it (acknowledgedAt) and the audit entry "Announcement read", in the same transaction as
     * the page load; opening it again changes nothing. Only the signed-in recipient's own row: 404 for someone who is
     * not a recipient, as recipient(...).
     */
    @Transactional
    public AnnouncementRecipient open(String email, Long announcementId) {
        AnnouncementRecipient recipient = recipient(email, announcementId);
        if (recipient.getAcknowledgedAt() == null) {
            recipient.setAcknowledgedAt(Instant.now());
            recipientRepository.save(recipient);
            auditService.record("ANNOUNCEMENT_ACKNOWLEDGED", recipient.getShip(),
                    "\"" + recipient.getAnnouncement().getTitle() + "\"");
        }
        return recipient;
    }

    // Every enabled user with a ship role on one of the target ships, or on any active ship for the fleet.
    private List<User> recipients(Targets targets) {
        if (targets.fleet()) {
            return userRepository.findAllByShipActiveTrueAndRoleNotAndEnabledTrue(Role.OFFICE_USER);
        }
        return targets.isEmpty() ? List.of()
                : userRepository.findAllByShipInAndRoleNotAndEnabledTrue(targets.ships(), Role.OFFICE_USER);
    }
}
