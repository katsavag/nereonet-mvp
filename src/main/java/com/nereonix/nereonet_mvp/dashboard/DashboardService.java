package com.nereonix.nereonet_mvp.dashboard;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.announcement.AnnouncementRecipientRepository;
import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateCounts;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.invitation.Invitation;
import com.nereonix.nereonet_mvp.core.invitation.InvitationRepository;
import com.nereonix.nereonet_mvp.core.library.LibraryService;
import com.nereonix.nereonet_mvp.core.request.ItemRequest;
import com.nereonix.nereonet_mvp.core.request.ItemRequestRepository;
import com.nereonix.nereonet_mvp.core.request.RequestStatus;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipCount;
import com.nereonix.nereonet_mvp.core.ship.ShipRepository;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

// The office home page. Read only: one "group by ship" count query per number, no lists loaded to count.
@Service
public class DashboardService {

    /** A certificate is an open item when it has expired or expires within this many days (UTC). */
    public static final int OPEN_ITEM_CERTIFICATE_DAYS = 30;

    private final ShipRepository shipRepository;
    private final UserRepository userRepository;
    private final InvitationRepository invitationRepository;
    private final CertificateRepository certificateRepository;
    private final AnnouncementRecipientRepository recipientRepository;
    private final ItemRequestRepository requestRepository;
    private final LibraryService libraryService;

    public DashboardService(ShipRepository shipRepository, UserRepository userRepository,
                            InvitationRepository invitationRepository, CertificateRepository certificateRepository,
                            AnnouncementRecipientRepository recipientRepository,
                            ItemRequestRepository requestRepository, LibraryService libraryService) {
        this.shipRepository = shipRepository;
        this.userRepository = userRepository;
        this.invitationRepository = invitationRepository;
        this.certificateRepository = certificateRepository;
        this.recipientRepository = recipientRepository;
        this.requestRepository = requestRepository;
        this.libraryService = libraryService;
    }

    /** One active ship's numbers. Certificate counts are as the badges: expired, 30, 31-60 and 61-90 days. */
    public record ShipCard(Ship ship, long crew, long pendingInvitations, long certificatesExpired,
                           long certificates30, long certificates60, long certificates90,
                           long announcementsNotAcknowledged, long requestsNotExported,
                           long libraryNotAcknowledged) {

        /** Expired certificates, certificates expiring within 30 days and approved requests not exported.
         *  The same rule as openItemCount and shipOverview for one ship. */
        public long openItems() {
            return certificatesExpired + certificates30 + requestsNotExported;
        }

        public boolean needsAttention() {
            return openItems() > 0;
        }
    }

    /** Ships that need attention first, then by name. The fleet totals add up the cards. */
    public record Dashboard(List<ShipCard> ships) {

        public long enabledCrew() {
            return ships.stream().mapToLong(ShipCard::crew).sum();
        }

        public long openItems() {
            return ships.stream().mapToLong(ShipCard::openItems).sum();
        }
    }

    /** The crew on board in one department; department is null for users without a rank. */
    public record Department(Rank.Department department, List<User> crew) {
    }

    /** The Overview tab of one ship (active or not): its open items and the crew on board by department. */
    public record ShipOverview(List<Certificate> certificates, List<ItemRequest> requests,
                               List<Department> departments) {

        public long openItems() {
            return certificates.size() + requests.size();
        }

        public long crewOnBoard() {
            return departments.stream().mapToLong(d -> d.crew().size()).sum();
        }
    }

    /** One ship's open items: expired or expiring certificates (soonest first), then requests not exported. */
    @Transactional(readOnly = true)
    public ShipOverview shipOverview(Ship ship) {
        List<Certificate> certificates = certificateRepository.findExpiringUntil(openItemsUntil(), ship.getId());
        List<ItemRequest> requests = requestRepository.findDecided(RequestStatus.APPROVED, ship.getId(), true);
        // Crew on board: enabled users with a ship role, as the fleet Overview counts them.
        List<User> crew = userRepository.findAllByShipAndRoleNotAndEnabledTrueOrderByFullNameAsc(ship, Role.OFFICE_USER);
        List<Department> departments = new ArrayList<>();
        for (Rank.Department department : Rank.Department.values()) {
            List<User> members = crew.stream()
                    .filter(u -> u.getRank() != null && u.getRank().department() == department).toList();
            if (!members.isEmpty()) {
                departments.add(new Department(department, members));
            }
        }
        List<User> noRank = crew.stream().filter(u -> u.getRank() == null).toList();
        if (!noRank.isEmpty()) {
            departments.add(new Department(null, noRank));
        }
        return new ShipOverview(certificates, requests, departments);
    }

    /** The number shipOverview(ship).openItems() gives, counted without loading the rows (the ship page's tab bar). */
    @Transactional(readOnly = true)
    public long openItemCount(Ship ship) {
        return certificateRepository.countExpiringUntil(openItemsUntil(), ship.getId())
                + requestRepository.countByStatusAndShipIdAndExportedAtIsNull(RequestStatus.APPROVED, ship.getId());
    }

    private static LocalDate openItemsUntil() {
        return LocalDate.now(ZoneOffset.UTC).plusDays(OPEN_ITEM_CERTIFICATE_DAYS);
    }

    @Transactional(readOnly = true)
    public Dashboard load() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Map<Long, Long> crew = byShip(userRepository.countEnabledByShip());
        Map<Long, Long> invitations = byShip(invitationRepository.countByStatusPerShip(Invitation.Status.PENDING));
        Map<Long, Long> announcements = byShip(recipientRepository.countUnacknowledgedByShip());
        Map<Long, Long> requests = byShip(requestRepository.countNotExportedByShip(RequestStatus.APPROVED));
        Map<Long, Long> library = libraryService.requiredNotAcknowledgedByShip();
        Map<Long, CertificateCounts> certificates = certificateRepository
                .countExpiringByShip(today, today.plusDays(OPEN_ITEM_CERTIFICATE_DAYS), today.plusDays(60), today.plusDays(90)).stream()
                .collect(Collectors.toMap(CertificateCounts::shipId, c -> c));
        CertificateCounts none = new CertificateCounts(null, 0, 0, 0, 0);

        List<ShipCard> cards = shipRepository.findAllByActiveTrueOrderByNameAsc().stream()
                .map(ship -> {
                    Long id = ship.getId();
                    CertificateCounts c = certificates.getOrDefault(id, none);
                    return new ShipCard(ship, crew.getOrDefault(id, 0L), invitations.getOrDefault(id, 0L),
                            c.expired(), c.within30(), c.within60(), c.within90(),
                            announcements.getOrDefault(id, 0L), requests.getOrDefault(id, 0L),
                            library.getOrDefault(id, 0L));
                })
                .sorted(Comparator.comparing(card -> !card.needsAttention())) // stable: keeps name order
                .toList();
        return new Dashboard(cards);
    }

    private static Map<Long, Long> byShip(List<ShipCount> counts) {
        return counts.stream().collect(Collectors.toMap(ShipCount::shipId, ShipCount::count));
    }
}
