package com.nereonix.nereonet_mvp.core.library;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.MessageSource;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.notification.NotificationService;
import com.nereonix.nereonet_mvp.core.notification.NotificationType;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

// A ship's library. A ship user only ever sees the items of their own ship that are not retired.
// Items are added, and new versions published, by the ship's Master (library) or by the office (officelibrary).
@Service
public class LibraryService {

    /** A ship can have at most this many items that are not retired. */
    public static final int MAX_ITEMS = 50;

    /** "Send reminder" can be used at most once in this time per item (P22). */
    public static final Duration REMINDER_INTERVAL = Duration.ofHours(24);

    private final LibraryItemRepository itemRepository;
    private final LibraryItemVersionRepository versionRepository;
    private final LibraryAcknowledgementRepository acknowledgementRepository;
    private final UserRepository userRepository;
    private final ShipRepository shipRepository;
    private final LibraryFileStorage fileStorage;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final MessageSource messageSource;

    public LibraryService(LibraryItemRepository itemRepository, LibraryItemVersionRepository versionRepository,
                          LibraryAcknowledgementRepository acknowledgementRepository, UserRepository userRepository, ShipRepository shipRepository,
                          LibraryFileStorage fileStorage, AuditService auditService,
                          NotificationService notificationService, MessageSource messageSource) {
        this.itemRepository = itemRepository;
        this.versionRepository = versionRepository;
        this.acknowledgementRepository = acknowledgementRepository;
        this.userRepository = userRepository;
        this.shipRepository = shipRepository;
        this.fileStorage = fileStorage;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.messageSource = messageSource;
    }

    /** The ship of this ship user. */
    @Transactional(readOnly = true)
    public Ship shipOf(String email) {
        return userRepository.findByEmail(email).orElseThrow().getShip();
    }

    /** The ship's items that are not retired, by category in enum order, each sorted by title. Empty categories are left out. */
    @Transactional(readOnly = true)
    public Map<LibraryCategory, List<LibraryItem>> itemsByCategory(Ship ship) {
        Map<LibraryCategory, List<LibraryItem>> byCategory = new EnumMap<>(LibraryCategory.class);
        for (LibraryItem item : itemRepository.findAllByShipAndRetiredFalseAndCurrentVersionIsNotNullOrderByTitleAsc(ship)) {
            byCategory.computeIfAbsent(item.getCategory(), c -> new ArrayList<>()).add(item);
        }
        return byCategory;
    }

    /** Any ship, for the office; otherwise 404. */
    @Transactional(readOnly = true)
    public Ship ship(Long id) {
        return shipRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * Adds an item with its first version, which the crew see at once. A Required item notifies every enabled
     * user on the ship except the one who added it. The form must be valid.
     * @throws LibraryFullException if the ship already has MAX_ITEMS items that are not retired
     */
    @Transactional
    public LibraryItem addItem(String actorEmail, Ship ship, LibraryItemForm form) {
        if (itemRepository.countByShipAndRetiredFalse(ship) >= MAX_ITEMS) {
            throw new LibraryFullException();
        }
        User actor = userRepository.findByEmail(actorEmail).orElseThrow();
        Instant now = Instant.now();

        LibraryItem item = new LibraryItem();
        item.setShip(ship);
        item.setTitle(form.getTitle());
        item.setCategory(form.getCategory());
        item.setRequired(form.getRequired());
        item.setCreatedBy(actor);
        item.setCreatedAt(now);
        itemRepository.save(item);

        LibraryItemVersion version = saveVersion(item, form.getVersionLabel(), form.getText(),
                form.hasFile() ? form.getFile() : null, form.hasFile() ? form.fileContentType() : null, actor, now);
        item.setCurrentVersion(version);

        auditService.record("LIBRARY_ITEM_ADDED", ship, "\"" + item.getTitle() + "\" (" + item.getCategory()
                + (item.isRequired() ? ", required" : "") + "), version " + version.getVersionLabel()
                + (version.isFile() ? ", file " + version.getFileName() : ", text"));

        if (item.isRequired()) {
            notifyCrew(item, actor);
        }
        return item;
    }

    /**
     * Publishes a new version of the item and makes it the current one. Earlier versions and their acknowledgements
     * stay in the database; the crew only see the new version, which nobody has acknowledged yet. A Required item
     * notifies the crew again, as in addItem. The form must be valid.
     * @throws VersionLabelUnchangedException if the label is the one of the current version
     */
    @Transactional
    public void publishNewVersion(String actorEmail, LibraryItem item, PublishVersionForm form) {
        LibraryItemVersion previous = item.getCurrentVersion();
        if (form.getVersionLabel().equals(previous.getVersionLabel())) {
            throw new VersionLabelUnchangedException();
        }
        User actor = userRepository.findByEmail(actorEmail).orElseThrow();
        LibraryItemVersion version = saveVersion(item, form.getVersionLabel(), form.getText(),
                form.hasFile() ? form.getFile() : null, form.hasFile() ? form.fileContentType() : null,
                actor, Instant.now());
        item.setCurrentVersion(version);
        itemRepository.save(item);

        auditService.record("LIBRARY_ITEM_VERSION_PUBLISHED", item.getShip(), "\"" + item.getTitle()
                + "\", version " + previous.getVersionLabel() + " → " + version.getVersionLabel()
                + (version.isFile() ? ", file " + version.getFileName() : ", text"));

        if (item.isRequired()) {
            notifyCrew(item, actor);
        }
    }

    /**
     * Retires the item: the crew no longer see it and it no longer counts towards MAX_ITEMS. Final: there is no
     * way back. The item stays in the database with its versions and acknowledgements.
     */
    @Transactional
    public void retire(String actorEmail, LibraryItem item) {
        item.setRetired(true);
        item.setRetiredAt(Instant.now());
        item.setRetiredBy(userRepository.findByEmail(actorEmail).orElseThrow());
        itemRepository.save(item);

        auditService.record("LIBRARY_ITEM_RETIRED", item.getShip(), "\"" + item.getTitle() + "\", version "
                + item.getCurrentVersion().getVersionLabel());
    }

    /**
     * Replaces the ship's particulars text (blank clears it) and records who edited it and when.
     * The IMO number and flag are not changed here. The form must be valid.
     */
    @Transactional
    public void updateParticulars(String actorEmail, Long shipId, ParticularsForm form) {
        Ship ship = ship(shipId);
        ship.setParticularsText(StringUtils.hasText(form.getText()) ? form.getText().strip() : null);
        ship.setParticularsUpdatedBy(userRepository.findByEmail(actorEmail).orElseThrow());
        ship.setParticularsUpdatedAt(Instant.now());
        shipRepository.save(ship);

        auditService.record("SHIP_PARTICULARS_UPDATED", ship, ship.getParticularsText() == null ? "cleared"
                : ship.getParticularsText().length() + " characters");
    }

    /** An item of this ship that is not retired, for the office; otherwise 404. */
    @Transactional(readOnly = true)
    public LibraryItem shipItem(Long shipId, Long id) {
        return itemRepository.findByIdAndShipAndRetiredFalseAndCurrentVersionIsNotNull(id, ship(shipId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Saves a version with the file (stored on disk) if there is one, otherwise with the text. */
    private LibraryItemVersion saveVersion(LibraryItem item, String label, String text, MultipartFile file,
                                           String fileContentType, User actor, Instant now) {
        LibraryItemVersion version = new LibraryItemVersion();
        version.setItem(item);
        version.setVersionLabel(label);
        version.setPublishedBy(actor);
        version.setPublishedAt(now);
        if (file != null) {
            version.setFileName(StringUtils.getFilename(file.getOriginalFilename()));
            version.setFileContentType(fileContentType);
            version.setFileSize(file.getSize());
            try (InputStream in = file.getInputStream()) {
                version.setStoragePath(fileStorage.save(in));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            version.setText(text);
        }
        return versionRepository.save(version);
    }

    /** Notifies every enabled user on the item's ship, except the actor, that there is a document to read. */
    private void notifyCrew(LibraryItem item, User actor) {
        String text = messageSource.getMessage("library.notification.newRequired", null, Locale.ENGLISH);
        for (User user : userRepository.findAllByShipAndRoleNotAndEnabledTrue(item.getShip(), Role.OFFICE_USER)) {
            if (!user.getId().equals(actor.getId())) {
                notificationService.notify(user, NotificationType.LIBRARY, item.getShip().getName(), text,
                        "/ship/library/" + item.getId());
            }
        }
    }

    /** An item of this ship user's own ship that is not retired; otherwise 404. */
    @Transactional(readOnly = true)
    public LibraryItem visibleItem(String email, Long id) {
        return itemRepository.findByIdAndShipAndRetiredFalseAndCurrentVersionIsNotNull(id, shipOf(email))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Ids of the items whose current version this user acknowledged. A Required item not in it is "to acknowledge". */
    @Transactional(readOnly = true)
    public Set<Long> acknowledgedItemIds(String email) {
        return acknowledgementRepository.findItemIdsAcknowledgedInCurrentVersion(email);
    }

    /** This user's acknowledgement of the item's current version, if any. */
    @Transactional(readOnly = true)
    public Optional<LibraryAcknowledgement> currentAcknowledgement(String email, LibraryItem item) {
        return acknowledgementRepository.findByVersionAndUserEmail(item.getCurrentVersion(), email);
    }

    /**
     * Records that this ship user read the current version of a Required item of their own ship.
     * Does nothing when already acknowledged. 404 for an item that is not visible or not Required.
     */
    @Transactional
    public void acknowledge(String email, Long id) {
        LibraryItem item = visibleItem(email, id);
        if (!item.isRequired()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        LibraryItemVersion version = item.getCurrentVersion();
        if (acknowledgementRepository.findByVersionAndUserEmail(version, email).isPresent()) {
            return;
        }
        LibraryAcknowledgement acknowledgement = new LibraryAcknowledgement();
        acknowledgement.setVersion(version);
        acknowledgement.setUser(userRepository.findByEmail(email).orElseThrow());
        acknowledgement.setAcknowledgedAt(Instant.now());
        acknowledgementRepository.save(acknowledgement);

        auditService.record("LIBRARY_ITEM_ACKNOWLEDGED", item.getShip(),
                "\"" + item.getTitle() + "\", version " + version.getVersionLabel());
    }

    // Acknowledgement overview (MASTER and OFFICE_USER, read only). "The crew" here is every enabled user with a
    // ship role who is on the ship now: someone who left the ship is not counted, even if they acknowledged.

    /** The crew count of the ship and, per item, how many of them acknowledged its current version. */
    @Transactional(readOnly = true)
    public AcknowledgementCounts acknowledgementCounts(Ship ship) {
        Map<Long, Long> byItem = acknowledgementRepository.countCurrentByItem(ship, Role.OFFICE_USER).stream()
                .collect(Collectors.toMap(ItemCount::itemId, ItemCount::count));
        return new AcknowledgementCounts(userRepository.countByShipAndRoleNotAndEnabledTrue(ship, Role.OFFICE_USER),
                byItem);
    }

    /** Who of the crew acknowledged the current version of this Required item and who not yet; 404 if not Required. */
    @Transactional(readOnly = true)
    public AcknowledgementOverview acknowledgementOverview(LibraryItem item) {
        if (!item.isRequired()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Map<Long, LibraryAcknowledgement> byUser = acknowledgementRepository
                .findAllByVersion(item.getCurrentVersion()).stream()
                .collect(Collectors.toMap(a -> a.getUser().getId(), a -> a));
        List<LibraryAcknowledgement> acknowledged = new ArrayList<>();
        List<User> notAcknowledged = new ArrayList<>();
        for (User user : userRepository.findAllByShipAndRoleNotAndEnabledTrueOrderByFullNameAsc(item.getShip(),
                Role.OFFICE_USER)) {
            LibraryAcknowledgement acknowledgement = byUser.get(user.getId());
            if (acknowledgement != null) {
                acknowledged.add(acknowledgement);
            } else {
                notAcknowledged.add(user);
            }
        }
        return new AcknowledgementOverview(item, acknowledged, notAcknowledged, nextReminderAt(item, Instant.now()));
    }

    /**
     * "Send reminder" on the acknowledgement overview (P22): notifies everyone of the crew (as in acknowledgementOverview)
     * who has not acknowledged the current version of this Required item, except the actor, and records it in the
     * audit log. At most once per REMINDER_INTERVAL per item. Returns how many were notified; 0 (and nothing recorded)
     * when there is nobody to remind.
     * @throws ReminderTooSoonException if the last reminder for this item was sent less than REMINDER_INTERVAL ago
     */
    @Transactional
    public int sendReminder(String actorEmail, LibraryItem item) {
        Instant now = Instant.now();
        if (nextReminderAt(item, now) != null) {
            throw new ReminderTooSoonException();
        }
        User actor = userRepository.findByEmail(actorEmail).orElseThrow();
        List<User> recipients = acknowledgementOverview(item).notAcknowledged().stream()
                .filter(user -> !user.getId().equals(actor.getId()))
                .toList();
        if (recipients.isEmpty()) {
            return 0;
        }
        String text = messageSource.getMessage("library.notification.reminder", null, Locale.ENGLISH);
        for (User user : recipients) {
            notificationService.notify(user, NotificationType.LIBRARY, item.getShip().getName(), text,
                    "/ship/library/" + item.getId());
        }
        item.setLastReminderAt(now);
        itemRepository.save(item);

        auditService.record("LIBRARY_ACKNOWLEDGEMENT_REMINDER_SENT", item.getShip(), "\"" + item.getTitle()
                + "\", version " + item.getCurrentVersion().getVersionLabel() + ", " + recipients.size()
                + (recipients.size() == 1 ? " person" : " people"));
        return recipients.size();
    }

    /** When the next reminder for this item may be sent, or null when it may be sent now. */
    private static Instant nextReminderAt(LibraryItem item, Instant now) {
        Instant last = item.getLastReminderAt();
        if (last == null) {
            return null;
        }
        Instant next = last.plus(REMINDER_INTERVAL);
        return next.isAfter(now) ? next : null;
    }

    /** Every version of the item, newest first, for its version history (Master and office, P22). */
    @Transactional(readOnly = true)
    public List<LibraryItemVersion> versions(LibraryItem item) {
        return versionRepository.findAllByItemOrderByPublishedAtDescIdDesc(item);
    }

    /** The file of the item's current version; 404 for a text version or a missing file. */
    public Resource currentFile(LibraryItem item) {
        LibraryItemVersion version = item.getCurrentVersion();
        if (!version.isFile()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Resource file = fileStorage.load(version.getStoragePath());
        if (!file.exists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return file;
    }
}
