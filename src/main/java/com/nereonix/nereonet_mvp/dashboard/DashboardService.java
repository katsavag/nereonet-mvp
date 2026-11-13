package com.nereonix.nereonet_mvp.dashboard;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipCount;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.core.vetting.InspectionState;
import com.nereonix.nereonet_mvp.core.vetting.RightShipView;
import com.nereonix.nereonet_mvp.dashboard.AttentionType.Group;
import com.nereonix.nereonet_mvp.dashboard.DashboardQueries.FleetShip;

/**
 * The office Overview (P09): a work list of open items ("Needs attention") and a fleet table, and the Overview tab of
 * the ship page. Read only.
 *
 * load() always runs the same 8 queries, whatever the size of the fleet: active ships, crew on board per ship, and
 * one per kind of open item (certificates, RightShip vetting, orders, invitations, announcements, library items).
 * See DashboardQueries.
 */
@Service
public class DashboardService {

    /** A certificate is an open item when it has expired or expires within this many days (UTC). */
    public static final int OPEN_ITEM_CERTIFICATE_DAYS = 30;

    /** A pending crew invitation is an open item this long after it was (last) sent, or once it has expired. */
    public static final Duration INVITATION_PENDING_LIMIT = Duration.ofHours(48);

    /** An announcement or a Required library item is an open item this long after it was sent / published. */
    public static final Duration ACKNOWLEDGEMENT_LIMIT = Duration.ofDays(7);

    /** The "Needs attention" card shows at most this many rows; the full lists are linked from it. */
    public static final int SHOWN_ITEMS = 50;

    private final DashboardQueries queries;
    private final UserRepository userRepository;
    private final CertificateRepository certificateRepository;
    private final ShipOrderService orderService;
    // The same setting InvitationService uses for an invitation's expiry date.
    private final int invitationValidDays;
    private final Clock clock = Clock.systemUTC();

    public DashboardService(DashboardQueries queries, UserRepository userRepository,
                            CertificateRepository certificateRepository, ShipOrderService orderService,
                            @Value("${app.invitation.valid-days:7}") int invitationValidDays) {
        this.queries = queries;
        this.userRepository = userRepository;
        this.certificateRepository = certificateRepository;
        this.orderService = orderService;
        this.invitationValidDays = invitationValidDays;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Open items
    // ---------------------------------------------------------------------------------------------------------------

    /** One row of the "Needs attention" card. */
    public sealed interface AttentionItem
            permits CertificateItem, VettingItem, OrderItem, InvitationItem, AnnouncementItem, LibraryAttentionItem {

        AttentionType type();

        Long id();

        /** The active ships it concerns: one, or for an announcement the ships of those who have not acknowledged. */
        Set<Long> shipIds();

        /** Orders the items of one type, smallest first: the most overdue or the oldest. */
        long sortKey();
    }

    /** An expired certificate, or one expiring within 30 days (type tells which); daysLeft is negative once expired. */
    public record CertificateItem(AttentionType type, Long id, Long userId, String certificateType,
                                  LocalDate expiresOn, long daysLeft, FleetShip ship, String fullName, Rank rank)
            implements AttentionItem {

        public Set<Long> shipIds() {
            return Set.of(ship.id());
        }

        public long sortKey() {
            return expiresOn.toEpochDay();
        }
    }

    /**
     * A vessel's RightShip standing that needs attention (P40). VETTING_INSPECTION: the inspection is expired,
     * unacceptable or expiring (state; validUntil and daysLeft, negative once expired, when there is a date).
     * VETTING_SCORE: the Safety Score is 2 or below (score). id: the vessel's current ShipRightShipStatus row.
     */
    public record VettingItem(AttentionType type, Long id, FleetShip ship, InspectionState state, LocalDate validUntil,
                              long daysLeft, Integer score) implements AttentionItem {

        public Set<Long> shipIds() {
            return Set.of(ship.id());
        }

        public long sortKey() {
            if (type == AttentionType.VETTING_SCORE) {
                return score;
            }
            return validUntil == null ? Long.MIN_VALUE : validUntil.toEpochDay();
        }
    }

    /** An order a ship has sent and the office never exported (P36); sentBy is null when that user was deleted. */
    public record OrderItem(Long id, FleetShip ship, String sentBy, Rank rank, Instant sentAt, long lines)
            implements AttentionItem {

        public AttentionType type() {
            return AttentionType.ORDER_NOT_EXPORTED;
        }

        public Set<Long> shipIds() {
            return Set.of(ship.id());
        }

        public long sortKey() {
            return sentAt == null ? Long.MIN_VALUE : sentAt.getEpochSecond();
        }
    }

    /** A pending crew invitation: expired, or sent (last) more than 48 hours ago. */
    public record InvitationItem(Long id, FleetShip ship, String fullName, Rank rank, Instant sentAt,
                                 Instant expiresAt, boolean expired) implements AttentionItem {

        public AttentionType type() {
            return AttentionType.INVITATION;
        }

        public Set<Long> shipIds() {
            return Set.of(ship.id());
        }

        public long sortKey() {
            return expiresAt.getEpochSecond();
        }
    }

    /** An announcement not acknowledged by everyone. fleetWide: sent to all ships; otherwise target is its one ship
     *  (when active), or null when it went to targetShips ships (P28). */
    public record AnnouncementItem(Long id, String title, boolean fleetWide, FleetShip target, int targetShips,
                                   Instant sentAt, long recipients, long acknowledged, Set<Long> shipIds)
            implements AttentionItem {

        public AttentionType type() {
            return AttentionType.ANNOUNCEMENT;
        }

        public long notAcknowledged() {
            return recipients - acknowledged;
        }

        public long sortKey() {
            return sentAt.getEpochSecond();
        }
    }

    /** A Required library item whose current version not everyone on board has acknowledged. */
    public record LibraryAttentionItem(Long id, String title, FleetShip ship, String versionLabel,
                                       Instant publishedAt, long onBoard, long acknowledged) implements AttentionItem {

        public AttentionType type() {
            return AttentionType.LIBRARY_ITEM;
        }

        public long notAcknowledged() {
            return onBoard - acknowledged;
        }

        public Set<Long> shipIds() {
            return Set.of(ship.id());
        }

        public long sortKey() {
            return publishedAt.getEpochSecond();
        }
    }

    /** Most urgent type first, then the most overdue or oldest. */
    static final Comparator<AttentionItem> URGENCY = Comparator
            .comparing(AttentionItem::type)
            .thenComparingLong(AttentionItem::sortKey)
            .thenComparing(AttentionItem::id);

    // ---------------------------------------------------------------------------------------------------------------
    // The page
    // ---------------------------------------------------------------------------------------------------------------

    /** One active ship in the fleet table. openItems counts every kind of open item, invitations included. */
    public record FleetRow(FleetShip ship, long crew, long certificateIssues, long notAcknowledged,
                           long ordersNotExported, long openItems) {
    }

    /** A kind of open item with its number, for the links to the full lists. */
    public record GroupCount(Group group, long count) {
    }

    /**
     * The Overview.
     * fleet        active ships, those with open items first, then by name
     * crewOnBoard  enabled crew on the active ships
     * openItems    every open item of the fleet (the summary line), whatever the filter
     * items        the open items the filter keeps, most urgent first
     * groups       the number of open items per kind (unfiltered), kinds without any left out
     * group, ship  the filter (/office?type=...&shipId=...), each null when not set
     * checkedAt    when this was loaded
     */
    public record Dashboard(List<FleetRow> fleet, long crewOnBoard, long openItems, List<AttentionItem> items,
                            List<GroupCount> groups, Group group, FleetShip ship, Instant checkedAt) {

        public boolean filtered() {
            return group != null || ship != null;
        }

        /** The rows on the card: all of them in a filtered (full) list, else the first SHOWN_ITEMS. */
        public List<AttentionItem> shown() {
            return filtered() || items.size() <= SHOWN_ITEMS ? items : items.subList(0, SHOWN_ITEMS);
        }

        public boolean truncated() {
            return shown().size() < items.size();
        }
    }

    /** The unfiltered Overview. */
    @Transactional(readOnly = true)
    public Dashboard load() {
        return load(null, null);
    }

    /**
     * The Overview, its open items filtered to one kind (group) and / or one active ship (shipId); null keeps all.
     * An unknown or inactive shipId filters nothing.
     */
    @Transactional(readOnly = true)
    public Dashboard load(Group group, Long shipId) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);

        List<FleetShip> ships = queries.activeShips();
        Map<Long, FleetShip> shipsById = ships.stream()
                .collect(Collectors.toMap(FleetShip::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<Long, Long> crew = userRepository.countEnabledByShip().stream()
                .collect(Collectors.toMap(ShipCount::shipId, ShipCount::count));

        List<AttentionItem> all = new ArrayList<>();
        for (DashboardQueries.CertificateRow c : queries.certificatesExpiringUntil(today.plusDays(OPEN_ITEM_CERTIFICATE_DAYS))) {
            AttentionType type = c.expiresOn().isBefore(today)
                    ? AttentionType.CERTIFICATE_EXPIRED : AttentionType.CERTIFICATE_EXPIRING;
            all.add(new CertificateItem(type, c.id(), c.userId(), c.type(), c.expiresOn(),
                    ChronoUnit.DAYS.between(today, c.expiresOn()), shipsById.get(c.shipId()), c.fullName(), c.rank()));
        }
        for (DashboardQueries.VettingRow v : queries.vettingNeedingAttention(
                today.plusDays(InspectionState.EXPIRING_DAYS), RightShipView.LOW_SCORE)) {
            FleetShip vessel = shipsById.get(v.shipId());
            InspectionState state = InspectionState.of(v.lastInspectionOutcome(), v.inspectionValidUntil(),
                    v.inspectionRequired(), today);
            if (state.needsAttention()) {
                long daysLeft = v.inspectionValidUntil() == null ? 0
                        : ChronoUnit.DAYS.between(today, v.inspectionValidUntil());
                all.add(new VettingItem(AttentionType.VETTING_INSPECTION, v.id(), vessel, state,
                        v.inspectionValidUntil(), daysLeft, v.safetyScore()));
            }
            if (v.safetyScore() != null && v.safetyScore() <= RightShipView.LOW_SCORE) {
                all.add(new VettingItem(AttentionType.VETTING_SCORE, v.id(), vessel, state, v.inspectionValidUntil(),
                        0, v.safetyScore()));
            }
        }
        for (DashboardQueries.OrderRow o : queries.ordersNotExported()) {
            all.add(new OrderItem(o.id(), shipsById.get(o.shipId()), o.sentBy(), o.rank(), o.sentAt(), o.lines()));
        }
        // expiresAt is (last) sent + the validity, so "sent more than 48 hours ago" is a bound on expiresAt.
        // It includes the expired ones: they were sent at least the validity ago.
        Duration validity = Duration.ofDays(invitationValidDays);
        Instant expiresBefore = now.plus(validity).minus(INVITATION_PENDING_LIMIT);
        for (DashboardQueries.InvitationRow i : queries.pendingInvitationsExpiringBefore(expiresBefore)) {
            all.add(new InvitationItem(i.id(), shipsById.get(i.shipId()), i.fullName(), i.rank(),
                    i.expiresAt().minus(validity), i.expiresAt(), i.expiresAt().isBefore(now)));
        }
        all.addAll(announcements(queries.announcementsSentBefore(now.minus(ACKNOWLEDGEMENT_LIMIT)), shipsById));
        for (DashboardQueries.LibraryRow l : queries.requiredItemsNotAcknowledged(now.minus(ACKNOWLEDGEMENT_LIMIT))) {
            all.add(new LibraryAttentionItem(l.id(), l.title(), shipsById.get(l.shipId()), l.versionLabel(),
                    l.publishedAt(), l.onBoard(), l.acknowledged()));
        }
        all.sort(URGENCY);

        List<FleetRow> fleet = ships.stream()
                .map(ship -> fleetRow(ship, crew.getOrDefault(ship.id(), 0L), all))
                .sorted(Comparator.comparing(row -> row.openItems() == 0)) // stable: keeps the name order
                .toList();

        Map<Group, Long> perGroup = new EnumMap<>(Group.class);
        all.forEach(item -> perGroup.merge(item.type().group(), 1L, Long::sum));
        List<GroupCount> groups = perGroup.entrySet().stream()
                .map(e -> new GroupCount(e.getKey(), e.getValue())).toList();

        FleetShip ship = shipId == null ? null : shipsById.get(shipId);
        List<AttentionItem> items = all.stream()
                .filter(item -> group == null || item.type().group() == group)
                .filter(item -> ship == null || item.shipIds().contains(ship.id()))
                .toList();

        return new Dashboard(fleet, fleet.stream().mapToLong(FleetRow::crew).sum(), all.size(), items, groups,
                group, ship, now);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // First-run setup (P25)
    // ---------------------------------------------------------------------------------------------------------------

    /** One step of the setup checklist: key names its texts (dashboard.setup.<key>.*), url the page it is done on. */
    public record SetupStep(String key, boolean done, String url) {
    }

    /** The setup checklist: add a ship, add or import its crew, add a library document, send an announcement. */
    public record Setup(List<SetupStep> steps) {

        /** Every step done: the checklist is not shown. */
        public boolean complete() {
            return steps.stream().allMatch(SetupStep::done);
        }

        /** There is a ship and crew, so there is work to list: the work list is shown (under the checklist). */
        public boolean workListShown() {
            return steps.get(0).done() && steps.get(1).done();
        }

        public long doneCount() {
            return steps.stream().filter(SetupStep::done).count();
        }

        /** The first step not done, or null. */
        public SetupStep next() {
            return steps.stream().filter(step -> !step.done()).findFirst().orElse(null);
        }
    }

    /**
     * The Overview's setup checklist. A step is done when its data exists: any ship; any user with a ship role or
     * pending crew invitation; any library item not retired; any announcement. The library step opens the first
     * ship's Library tab (Ships while there is none). One query.
     */
    @Transactional(readOnly = true)
    public Setup setup() {
        DashboardQueries.SetupRow row = queries.setup();
        String library = row.firstShipId() == null ? "/office/ships" : "/office/ships/" + row.firstShipId() + "/library";
        return new Setup(List.of(
                new SetupStep("ship", row.ships() > 0, "/office/ships"),
                new SetupStep("crew", row.crewUsers() + row.crewInvitations() > 0, "/office/crew"),
                new SetupStep("library", row.libraryItems() > 0, library),
                new SetupStep("announcement", row.announcements() > 0, "/office/announcements")));
    }

    /** One item per announcement that someone on an active ship has not acknowledged. */
    private static List<AttentionItem> announcements(List<DashboardQueries.AnnouncementShipRow> rows,
                                                     Map<Long, FleetShip> activeShips) {
        Map<Long, List<DashboardQueries.AnnouncementShipRow>> byAnnouncement = rows.stream()
                .collect(Collectors.groupingBy(DashboardQueries.AnnouncementShipRow::id, LinkedHashMap::new,
                        Collectors.toList()));
        List<AttentionItem> items = new ArrayList<>();
        byAnnouncement.forEach((id, perShip) -> {
            Set<Long> shipIds = new LinkedHashSet<>();
            long recipients = 0;
            long acknowledged = 0;
            for (DashboardQueries.AnnouncementShipRow row : perShip) {
                recipients += row.recipients();
                acknowledged += row.acknowledged();
                if (row.acknowledged() < row.recipients() && activeShips.containsKey(row.recipientShipId())) {
                    shipIds.add(row.recipientShipId());
                }
            }
            if (!shipIds.isEmpty()) {
                DashboardQueries.AnnouncementShipRow first = perShip.getFirst();
                FleetShip target = first.shipId() == null ? null : activeShips.get(first.shipId());
                items.add(new AnnouncementItem(id, first.title(), first.fleetWide(), target, first.targetShips(),
                        first.sentAt(), recipients, acknowledged, Set.copyOf(shipIds)));
            }
        });
        return items;
    }

    private static FleetRow fleetRow(FleetShip ship, long crew, List<AttentionItem> items) {
        long certificates = 0;
        long acknowledgements = 0;
        long orders = 0;
        long open = 0;
        for (AttentionItem item : items) {
            if (!item.shipIds().contains(ship.id())) {
                continue;
            }
            open++;
            switch (item.type().group()) {
                case CERTIFICATES -> certificates++;
                case ACKNOWLEDGEMENTS -> acknowledgements++;
                case ORDERS -> orders++;
                case INVITATIONS, VETTING -> { }
            }
        }
        return new FleetRow(ship, crew, certificates, acknowledgements, orders, open);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The ship page's Overview tab
    // ---------------------------------------------------------------------------------------------------------------

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
        return LocalDate.now(clock).plusDays(OPEN_ITEM_CERTIFICATE_DAYS);
    }
}
