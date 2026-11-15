package com.nereonix.nereonet_mvp.dashboard;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;

/**
 * The Overview tab of the ship page: one ship's open items, by the fleet Overview's rule for certificates and orders,
 * and its crew on board by department.
 */
@Service
public class ShipOverviewService {

    /** The crew on board in one department; department is null for users without a rank. */
    public record Department(Rank.Department department, List<User> crew) {
    }

    /** The Overview tab of one ship (active or not): its open items and the crew on board by department. */
    public record ShipOverview(List<Certificate> certificates, List<ShipOrder> orders,
                               List<Department> departments) {

        public long openItems() {
            return certificates.size() + orders.size();
        }

        public long crewOnBoard() {
            return departments.stream().mapToLong(d -> d.crew().size()).sum();
        }
    }

    private final CertificateRepository certificateRepository;
    private final ShipOrderService orderService;
    private final UserRepository userRepository;
    private final Clock clock = Clock.systemUTC();

    public ShipOverviewService(CertificateRepository certificateRepository, ShipOrderService orderService,
                               UserRepository userRepository) {
        this.certificateRepository = certificateRepository;
        this.orderService = orderService;
        this.userRepository = userRepository;
    }

    /** One ship's open items: expired or expiring certificates (soonest first), then orders sent and not exported. */
    @Transactional(readOnly = true)
    public ShipOverview shipOverview(Ship ship) {
        List<Certificate> certificates = certificateRepository.findExpiringUntil(openItemsUntil(), ship.getId());
        List<ShipOrder> orders = orderService.findNotExported(ship.getId());
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
        return new ShipOverview(certificates, orders, departments);
    }

    /** The number shipOverview(ship).openItems() gives, counted without loading the rows (the ship page's tab bar). */
    @Transactional(readOnly = true)
    public long openItemCount(Ship ship) {
        return certificateRepository.countExpiringUntil(openItemsUntil(), ship.getId())
                + orderService.countNotExported(ship.getId());
    }

    private LocalDate openItemsUntil() {
        return LocalDate.now(clock).plusDays(NeedsAttention.OPEN_ITEM_CERTIFICATE_DAYS);
    }
}
