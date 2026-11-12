package com.nereonix.nereonet_mvp.announcement;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
     * announcements only. With a shipId, only the ones sent to that ship or to all ships. The search is the title.
     * Sort keys: sentAt (default, newest first), title, target (All ships first, then by ship name).
     */
    @Transactional(readOnly = true)
    public Page<Row> findAll(Long shipId, ListQuery list) {
        Page<Announcement> page = listQueries.page(Announcement.class, list, (root, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (shipId != null) {
                Join<Announcement, Ship> ship = root.join("ship", JoinType.LEFT);
                where.add(cb.or(cb.isNull(ship.get("id")), cb.equal(ship.get("id"), shipId)));
            }
            if (list.getContains() != null) {
                where.add(ListQueries.contains(cb, root.get("title"), list.getContains()));
            }
            return where;
        }, (root, cb) -> {
            Expression<Instant> sentAt = root.get("createdAt");
            return switch (list.getSort()) {
                case "title" -> List.of(cb.lower(root.get("title")), sentAt);
                case "target" -> List.of(cb.coalesce(cb.lower(root.join("ship", JoinType.LEFT).get("name")), ""),
                        sentAt);
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

    @Transactional(readOnly = true)
    public Recipients findRecipients(Announcement announcement) {
        List<AnnouncementRecipient> all = recipientRepository.findAllByAnnouncementOrderByUserFullNameAsc(announcement);
        return new Recipients(
                all.stream().filter(r -> r.getAcknowledgedAt() != null)
                        .sorted(Comparator.comparing(AnnouncementRecipient::getAcknowledgedAt)).toList(),
                all.stream().filter(r -> r.getAcknowledgedAt() == null).toList());
    }

    /** How many people on one ship an announcement would go to (the review step, P10). */
    public record ShipRecipients(Ship ship, int people) {
    }

    /** A sent announcement and the number of people it went to, counted when it was sent. */
    public record Sent(Announcement announcement, int recipients) {
    }

    /** The ship, if it exists and is active: only active ships get announcements. */
    @Transactional(readOnly = true)
    public Optional<Ship> findActiveShip(Long shipId) {
        return shipRepository.findById(shipId).filter(Ship::isActive);
    }

    /**
     * Who an announcement to this ship (null: the whole fleet) would go to if it were sent now, per ship by name: the
     * same rule as create(). For the fleet every active ship is listed, also one with nobody on board.
     */
    @Transactional(readOnly = true)
    public List<ShipRecipients> recipientsPerShip(Long shipId) {
        Ship ship = shipId == null ? null : activeShip(shipId);
        Map<Long, Long> perShip = recipients(ship).stream()
                .collect(Collectors.groupingBy(user -> user.getShip().getId(), Collectors.counting()));
        List<Ship> ships = ship == null ? findActiveShips() : List.of(ship);
        return ships.stream()
                .map(s -> new ShipRecipients(s, perShip.getOrDefault(s.getId(), 0L).intValue()))
                .toList();
    }

    /**
     * Sends an announcement to one active ship, or to the whole fleet when shipId is null. The recipients are fixed
     * now: every enabled user with a ship role who is on that ship (on any active ship for the fleet), counted again
     * here, whatever the review page showed. Each recipient gets an in-app notification, "Announcement · <title>",
     * that opens the announcement (P27).
     */
    @Transactional
    public Sent create(String title, String body, Long shipId, String actorEmail) {
        Ship ship = shipId == null ? null : activeShip(shipId);

        Announcement announcement = new Announcement();
        announcement.setTitle(title.strip());
        announcement.setBody(body.strip());
        announcement.setShip(ship);
        announcement.setCreatedBy(userRepository.findByEmail(actorEmail).orElseThrow());
        announcement.setCreatedAt(Instant.now());
        announcementRepository.save(announcement);

        List<User> users = recipients(ship);
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
                    "/ship/announcements/" + announcement.getId());
        }

        auditService.record("ANNOUNCEMENT_CREATED", ship,
                announcement.getTitle() + " (" + (ship == null ? "all ships" : ship.getName()) + ", "
                        + users.size() + " recipients)");
        return new Sent(announcement, users.size());
    }

    /** The announcements this ship user is a recipient of, newest first, each with their acknowledgement (P27). */
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
     * Records that this recipient read the announcement (P27), in the audit log too. Returns false and changes nothing
     * when they had already acknowledged it. 404 for someone who is not a recipient.
     */
    @Transactional
    public boolean acknowledge(String email, Long announcementId) {
        AnnouncementRecipient recipient = recipient(email, announcementId);
        if (recipient.getAcknowledgedAt() != null) {
            return false;
        }
        recipient.setAcknowledgedAt(Instant.now());
        recipientRepository.save(recipient);
        auditService.record("ANNOUNCEMENT_ACKNOWLEDGED", recipient.getShip(),
                "\"" + recipient.getAnnouncement().getTitle() + "\"");
        return true;
    }

    // 400 for a ship that does not exist or is not active.
    private Ship activeShip(Long shipId) {
        return findActiveShip(shipId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such active ship"));
    }

    // Every enabled user with a ship role on this ship, or on any active ship when ship is null.
    private List<User> recipients(Ship ship) {
        return ship == null
                ? userRepository.findAllByShipActiveTrueAndRoleNotAndEnabledTrue(Role.OFFICE_USER)
                : userRepository.findAllByShipAndRoleNotAndEnabledTrue(ship, Role.OFFICE_USER);
    }
}
