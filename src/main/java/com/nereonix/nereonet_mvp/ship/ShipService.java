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
import com.nereonix.nereonet_mvp.core.library.LibraryItemRepository;
import com.nereonix.nereonet_mvp.core.ship.ImoNumber;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.ship.ShipType;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.dashboard.DashboardService;

@Service
public class ShipService {

    private final ShipRepository shipRepository;
    private final AuditService auditService;
    private final DashboardService dashboardService;
    private final UserRepository userRepository;
    private final InvitationRepository invitationRepository;
    private final CertificateRepository certificateRepository;
    private final LibraryItemRepository libraryItemRepository;
    private final ItemRepository itemRepository;
    private final AnnouncementRepository announcementRepository;

    public ShipService(ShipRepository shipRepository, AuditService auditService, DashboardService dashboardService,
                       UserRepository userRepository, InvitationRepository invitationRepository,
                       CertificateRepository certificateRepository, LibraryItemRepository libraryItemRepository,
                       ItemRepository itemRepository, AnnouncementRepository announcementRepository) {
        this.shipRepository = shipRepository;
        this.auditService = auditService;
        this.dashboardService = dashboardService;
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
                dashboardService.openItemCount(ship),
                userRepository.countByShip(ship)
                        + invitationRepository.countByStatusAndShip(Invitation.Status.PENDING, ship),
                certificateRepository.countExpiringUntil(today.plusDays(Certificate.REMINDER_DAYS.getLast()), id),
                libraryItemRepository.countByShipAndRetiredFalseAndCurrentVersionIsNotNull(ship),
                itemRepository.countByShipId(id),
                announcementRepository.countForShip(id));
    }

    /**
     * Saves a new, active ship. imoNumber may be "IMO 9074729" or "9074729"; only the digits are stored.
     * Throws ImoNumberAlreadyExistsException if a ship with that IMO number exists.
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
        return ship;
    }

    // Everything except the IMO number, which never changes.
    @Transactional
    public void update(Long id, String name, ShipType type, String flag) {
        Ship ship = get(id);
        apply(ship, name, type, flag);
        auditService.record("SHIP_UPDATED", ship, describe(ship));
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
