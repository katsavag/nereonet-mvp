package com.nereonix.nereonet_mvp.announcement;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

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
// the crew confirmed.
@Service
public class AnnouncementService {

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementRecipientRepository recipientRepository;
    private final ShipRepository shipRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ListQueries listQueries;

    public AnnouncementService(AnnouncementRepository announcementRepository,
                               AnnouncementRecipientRepository recipientRepository, ShipRepository shipRepository,
                               UserRepository userRepository, AuditService auditService, ListQueries listQueries) {
        this.announcementRepository = announcementRepository;
        this.recipientRepository = recipientRepository;
        this.shipRepository = shipRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.listQueries = listQueries;
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
     * here, whatever the review page showed.
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

        auditService.record("ANNOUNCEMENT_CREATED", ship,
                announcement.getTitle() + " (" + (ship == null ? "all ships" : ship.getName()) + ", "
                        + users.size() + " recipients)");
        return new Sent(announcement, users.size());
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
