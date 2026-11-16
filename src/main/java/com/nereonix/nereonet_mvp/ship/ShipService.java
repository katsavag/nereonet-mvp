package com.nereonix.nereonet_mvp.ship;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.nereonix.nereonet_mvp.core.announcement.AnnouncementRepository;
import com.nereonix.nereonet_mvp.core.audit.AuditService;
import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.invitation.Invitation;
import com.nereonix.nereonet_mvp.core.invitation.InvitationRepository;
import com.nereonix.nereonet_mvp.core.item.ItemRepository;
import com.nereonix.nereonet_mvp.core.item.StarterItems;
import com.nereonix.nereonet_mvp.core.library.LibraryItemRepository;
import com.nereonix.nereonet_mvp.core.ship.ImoNumber;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.ship.ShipType;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.dashboard.ShipOverviewService;
import com.nereonix.nereonet_mvp.requirement.RequirementService;

@Service
public class ShipService {

    private final ShipRepository shipRepository;
    private final AuditService auditService;
    private final ShipOverviewService shipOverviewService;
    private final UserRepository userRepository;
    private final InvitationRepository invitationRepository;
    private final CertificateRepository certificateRepository;
    private final LibraryItemRepository libraryItemRepository;
    private final ItemRepository itemRepository;
    private final AnnouncementRepository announcementRepository;
    private final RequirementService requirementService;
    private final StarterItems starterItems;

    public ShipService(ShipRepository shipRepository, AuditService auditService, ShipOverviewService shipOverviewService,
                       UserRepository userRepository, InvitationRepository invitationRepository,
                       CertificateRepository certificateRepository, LibraryItemRepository libraryItemRepository,
                       ItemRepository itemRepository, AnnouncementRepository announcementRepository,
                       RequirementService requirementService, StarterItems starterItems) {
        this.starterItems = starterItems;
        this.requirementService = requirementService;
        this.shipRepository = shipRepository;
        this.auditService = auditService;
        this.shipOverviewService = shipOverviewService;
        this.userRepository = userRepository;
        this.invitationRepository = invitationRepository;
        this.certificateRepository = certificateRepository;
        this.libraryItemRepository = libraryItemRepository;
        this.itemRepository = itemRepository;
        this.announcementRepository = announcementRepository;
    }

    @Transactional(readOnly = true)
    public List<Ship> findAll() {
        return shipRepository.findAllByOrderByNameAsc();
    }

    // The vessel with this IMO number (7 digits), if the fleet has it ("Already in the fleet").
    @Transactional(readOnly = true)
    public java.util.Optional<Ship> findByImoNumber(String digits) {
        return shipRepository.findByImoNumber(digits);
    }

    // 404 if there is no such ship.
    @Transactional(readOnly = true)
    public Ship get(Long id) {
        return shipRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * The numbers on the ship page's tabs, one count query each: what each tab lists (see ShipTabCounts). Crew: every
     * user on the ship and its pending invitations; Certificates: expired or expiring within 90 days, as on
     * /office/certificates; Library: items not retired; Announcements: sent to this ship or to all ships.
     */
    @Transactional(readOnly = true)
    public ShipTabCounts tabCounts(Ship ship) {
        Long id = ship.getId();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return new ShipTabCounts(
                shipOverviewService.openItemCount(ship),
                userRepository.countByShip(ship)
                        + invitationRepository.countByStatusAndShip(Invitation.Status.PENDING, ship),
                certificateRepository.countExpiringUntil(today.plusDays(Certificate.REMINDER_DAYS.getLast()), id),
                libraryItemRepository.countByShipAndRetiredFalseAndCurrentVersionIsNotNull(ship),
                itemRepository.countByShipId(id),
                announcementRepository.countForShip(id));
    }

    /**
     * Saves a new, active ship. imoNumber may be "IMO 9074729" or "9074729"; only the digits are stored.
     * Throws ImoNumberAlreadyExistsException if a ship with that IMO number exists. The vessel starts with the
     * required documents of its type's defaults (StarterRequirements), as suggestions to review, and with the standard
     * galley list (StarterItems, ITEMS_STARTED).
     */
    @Transactional
    public Ship create(String imoNumber, String name, ShipType type, String flag) {
        String digits = ImoNumber.normalize(imoNumber);
        if (!ImoNumber.isValid(digits)) {
            throw new IllegalArgumentException("Invalid IMO number");
        }
        if (shipRepository.existsByImoNumber(digits)) {
            throw new ImoNumberAlreadyExistsException();
        }
        Ship ship = new Ship();
        ship.setImoNumber(digits);
        apply(ship, name, type, flag);
        ship.setActive(true);
        ship.setCreatedAt(Instant.now());
        shipRepository.save(ship);
        auditService.record("SHIP_CREATED", ship, describe(ship));
        requirementService.startWithDefaults(ship);
        starterItems.addTo(ship);
        return ship;
    }

    // Everything except the IMO number, which never changes. When the type changes and resetRequirements is
    // set, the vessel's required documents become the new type's defaults (REQUIREMENTS_RESET); otherwise they stay.
    @Transactional
    public void update(Long id, String name, ShipType type, String flag, boolean resetRequirements) {
        Ship ship = get(id);
        ShipType before = ship.getType();
        apply(ship, name, type, flag);
        auditService.record("SHIP_UPDATED", ship, describe(ship));
        if (resetRequirements && before != ship.getType()) {
            requirementService.reset(ship);
        }
    }

    @Transactional
    public void setActive(Long id, boolean active) {
        Ship ship = get(id);
        ship.setActive(active);
        auditService.record(active ? "SHIP_ACTIVATED" : "SHIP_DEACTIVATED", ship, null);
    }

    // For the audit log, e.g. "IMO 9074729, MV Aegean Spirit, BULK_CARRIER, GR"
    private static String describe(Ship ship) {
        return "IMO " + ship.getImoNumber() + ", " + ship.getName() + ", "
                + (ship.getType() == null ? "-" : ship.getType()) + ", "
                + (ship.getFlag() == null ? "-" : ship.getFlag());
    }

    // flag may be "Greece (GR)" or "GR"; only the country code is stored.
    private static void apply(Ship ship, String name, ShipType type, String flag) {
        if (!Countries.isValidOrBlank(flag)) {
            throw new IllegalArgumentException("Unknown country");
        }
        ship.setName(name.trim());
        ship.setType(type);
        ship.setFlag(Countries.toCode(flag));
    }
}
