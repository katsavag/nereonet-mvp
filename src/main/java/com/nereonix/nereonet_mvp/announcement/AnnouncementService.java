package com.nereonix.nereonet_mvp.announcement;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

// Announcements sent by the office. There is no edit or delete: an announcement is the proof of what
// the crew confirmed.
@Service
public class AnnouncementService {

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementRecipientRepository recipientRepository;
    private final ShipRepository shipRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public AnnouncementService(AnnouncementRepository announcementRepository,
                               AnnouncementRecipientRepository recipientRepository, ShipRepository shipRepository,
                               UserRepository userRepository, AuditService auditService) {
        this.announcementRepository = announcementRepository;
        this.recipientRepository = recipientRepository;
        this.shipRepository = shipRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    /** An announcement with its recipient counts, for the list. */
    public record Row(Announcement announcement, long recipients, long acknowledged) {
    }

    /** The recipients of one announcement: acknowledged (earliest first) and not yet (by name). */
    public record Recipients(List<AnnouncementRecipient> acknowledged, List<AnnouncementRecipient> pending) {
    }

    /** Newest first. With a shipId, only the ones sent to that ship or to all ships. */
    @Transactional(readOnly = true)
    public List<Row> findAll(Long shipId) {
        Map<Long, RecipientCount> counts = recipientRepository.countAll().stream()
                .collect(Collectors.toMap(RecipientCount::announcementId, Function.identity()));
        List<Announcement> announcements = shipId == null
                ? announcementRepository.findAllByOrderByCreatedAtDescIdDesc()
                : announcementRepository.findAllForShip(shipId);
        return announcements.stream()
                .map(a -> {
                    RecipientCount count = counts.get(a.getId());
                    return count == null ? new Row(a, 0, 0) : new Row(a, count.recipients(), count.acknowledged());
                })
                .toList();
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

    /**
     * Sends an announcement to one active ship, or to the whole fleet when shipId is null. The recipients are fixed
     * now: every enabled user with a ship role who is on that ship (on any active ship for the fleet).
     * Returns the new announcement.
     */
    @Transactional
    public Announcement create(String title, String body, Long shipId, String actorEmail) {
        Ship ship = null;
        if (shipId != null) {
            ship = shipRepository.findById(shipId)
                    .filter(Ship::isActive)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such active ship"));
        }

        Announcement announcement = new Announcement();
        announcement.setTitle(title.strip());
        announcement.setBody(body.strip());
        announcement.setShip(ship);
        announcement.setCreatedBy(userRepository.findByEmail(actorEmail).orElseThrow());
        announcement.setCreatedAt(Instant.now());
        announcementRepository.save(announcement);

        List<User> users = ship == null
                ? userRepository.findAllByShipActiveTrueAndRoleNotAndEnabledTrue(Role.OFFICE_USER)
                : userRepository.findAllByShipAndRoleNotAndEnabledTrue(ship, Role.OFFICE_USER);
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
        return announcement;
    }
}
