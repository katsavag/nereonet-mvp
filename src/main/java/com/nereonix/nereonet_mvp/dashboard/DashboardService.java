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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nereonix.nereonet_mvp.core.alert.Alert;
import com.nereonix.nereonet_mvp.core.alert.AlertKey;
import com.nereonix.nereonet_mvp.core.alert.AlertKind;
import com.nereonix.nereonet_mvp.core.alert.AlertLevels;
import com.nereonix.nereonet_mvp.core.alert.AlertSnooze;
import com.nereonix.nereonet_mvp.core.alert.SnoozeService;
import com.nereonix.nereonet_mvp.core.audit.AuditEntry;
import com.nereonix.nereonet_mvp.core.audit.AuditRepository;
import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository;
import com.nereonix.nereonet_mvp.core.list.ListQueries;
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.order.ShipOrderService;
import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.core.rfq.RfqComparison;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;
import com.nereonix.nereonet_mvp.core.user.UserRepository;
import com.nereonix.nereonet_mvp.core.vetting.InspectionState;
import com.nereonix.nereonet_mvp.core.vetting.ObservationType;
import com.nereonix.nereonet_mvp.core.vetting.RightShipView;
import com.nereonix.nereonet_mvp.core.vetting.ShipRightShipStatus;
import com.nereonix.nereonet_mvp.core.vetting.ShipRightShipStatusRepository;
import com.nereonix.nereonet_mvp.core.vetting.SireObservation;
import com.nereonix.nereonet_mvp.core.vetting.SireReportState;
import com.nereonix.nereonet_mvp.core.vetting.SireService;
import com.nereonix.nereonet_mvp.core.vetting.SireView;
import com.nereonix.nereonet_mvp.dashboard.AttentionType.Group;
import com.nereonix.nereonet_mvp.dashboard.DashboardQueries.FleetShip;
import com.nereonix.nereonet_mvp.requirement.Compliance;
import com.nereonix.nereonet_mvp.requirement.ComplianceService;
import com.nereonix.nereonet_mvp.requirement.RequiredDocument;

/**
 * The office Overview (P09, P43): four stat tiles, a work list of open items ("Needs attention", paged, filtered by
 * category or vessel), and the cards By category, Next 30 days, Fleet and Recent activity; also the Overview tab of
 * the ship page.
 *
 * load() always runs the same 13 queries, whatever the size of the fleet: the active ships with their crew, the
 * certificates, the required documents' rules, the vessels' RightShip rows, SIRE 2.0 (two, SireService.attention),
 * the orders, the open requests for quotes (P44), the invitations, the announcements, the library items, the
 * snoozes (SnoozeService.sync, which also ends the snoozes that no longer apply: a write, so load() is not
 * read-only) and the newest audit entries.
 * Required documents (P42) add two more when any rule is set: the crew on board and their certificates
 * (ComplianceService.onBoard). DashboardFlowTest counts them. Snoozed items (P43) are left out of everything but
 * the "snoozed" detail of the Items tile.
 */
@Service
public class DashboardService {

    /** A certificate is an open item when it has expired or expires within this many days (UTC). */
    public static final int OPEN_ITEM_CERTIFICATE_DAYS = 30;
    /** The snooze sync (P43) looks this far ahead for certificates: as far as the Certificates list alerts. */
    public static final int SYNC_CERTIFICATE_DAYS = 90;

    /** A pending crew invitation is an open item this long after it was (last) sent, or once it has expired. */
    public static final Duration INVITATION_PENDING_LIMIT = Duration.ofHours(48);

    /** An announcement or a Required library item is an open item this long after it was sent / published. */
    public static final Duration ACKNOWLEDGEMENT_LIMIT = Duration.ofDays(7);

    /** The "Next 30 days" card looks this far ahead and shows at most UPCOMING_SHOWN dates. */
    public static final int UPCOMING_DAYS = 30;
    public static final int UPCOMING_SHOWN = 8;

    /** The Fleet card shows at most this many vessels; Recent activity this many audit entries. */
    public static final int FLEET_SHOWN = 8;
    public static final int RECENT_SHOWN = 5;

    /** The sort keys of the "Needs attention" list: most urgent first (the default), or by date. */
    public static final String SORT_URGENCY = "urgency";
    public static final String SORT_DATE = "date";

    private final DashboardQueries queries;
    private final UserRepository userRepository;
    private final CertificateRepository certificateRepository;
    private final ShipOrderService orderService;
    private final SireService sireService;
    private final ComplianceService complianceService;
    private final ShipRightShipStatusRepository rightShipRepository;
    private final SnoozeService snoozeService;
    private final AuditRepository auditRepository;
    // The same setting InvitationService uses for an invitation's expiry date.
    private final int invitationValidDays;
    private final Clock clock = Clock.systemUTC();

    public DashboardService(DashboardQueries queries, UserRepository userRepository,
                            CertificateRepository certificateRepository, ShipOrderService orderService,
                            SireService sireService, ComplianceService complianceService,
                            ShipRightShipStatusRepository rightShipRepository, SnoozeService snoozeService,
                            AuditRepository auditRepository,
                            @Value("${app.invitation.valid-days:7}") int invitationValidDays) {
        this.sireService = sireService;
        this.complianceService = complianceService;
        this.rightShipRepository = rightShipRepository;
        this.snoozeService = snoozeService;
        this.auditRepository = auditRepository;
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
            permits CertificateItem, DocumentsItem, VettingItem, SireItem, OrderItem, RfqItem, InvitationItem, AnnouncementItem,
            LibraryAttentionItem {

        AttentionType type();

        Long id();

        /** The active ships it concerns: one, or for an announcement the ships of those who have not acknowledged. */
        Set<Long> shipIds();

        /** Orders the items of one type, smallest first: the most overdue or the oldest. */
        long sortKey();

        /** The item as an alert the office can snooze (P43), or null for the kinds that cannot be. */
        default Alert alert() {
            return null;
        }

        /** The date that drives the item (an expiry, a deadline), or null: the date sort and the Next 30 days card. */
        default LocalDate dueDate() {
            return null;
        }
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

        public Alert alert() {
            return Alert.of(type == AttentionType.CERTIFICATE_EXPIRED ? AlertKind.CERTIFICATE_EXPIRED
                    : AlertKind.CERTIFICATE_EXPIRING, id, AlertLevels.ofDaysLeft(daysLeft));
        }

        public LocalDate dueDate() {
            return expiresOn;
        }
    }

    /**
     * A seafarer on board (enabled, with a rank, on an active vessel) missing required documents, an expired one
     * included (P42): missing of required, and the types (most urgent first). id: the user. Most missing first.
     */
    public record DocumentsItem(Long id, String fullName, Rank rank, FleetShip ship, long missing, long required,
                                List<String> types) implements AttentionItem {

        public AttentionType type() {
            return AttentionType.DOCUMENTS_MISSING;
        }

        public Set<Long> shipIds() {
            return Set.of(ship.id());
        }

        public long sortKey() {
            return -missing;
        }

        public Alert alert() {
            return Alert.of(AlertKind.DOCUMENTS_MISSING, id, (int) -missing);
        }
    }

    /**
     * A vessel's SIRE 2.0 item that needs attention (P41), each linking to the vessel's SIRE page.
     * SIRE_COMMENTS: the operator comments are due on date (daysLeft, negative once overdue); id the inspection.
     * SIRE_REPORT: the report is current until date (state ENDING or NOT_CURRENT); id the inspection.
     * SIRE_OBSERVATION: an open observation (question, observationType) past its target date; id the observation.
     */
    public record SireItem(AttentionType type, Long id, FleetShip ship, LocalDate date, long daysLeft,
                           SireReportState state, String question, ObservationType observationType, int level)
            implements AttentionItem {

        public Set<Long> shipIds() {
            return Set.of(ship.id());
        }

        public long sortKey() {
            return date.toEpochDay();
        }

        public Alert alert() {
            return Alert.of(switch (type) {
                case SIRE_COMMENTS -> AlertKind.SIRE_COMMENTS;
                case SIRE_REPORT -> AlertKind.SIRE_REPORT;
                default -> AlertKind.SIRE_OBSERVATION;
            }, id, level);
        }

        public LocalDate dueDate() {
            return date;
        }
    }

    /**
     * A vessel's RightShip standing that needs attention (P40). VETTING_INSPECTION: the inspection is expired,
     * unacceptable or expiring (state; validUntil and daysLeft, negative once expired, when there is a date).
     * VETTING_SCORE: the Safety Score is 2 or below (score). id: the vessel's current ShipRightShipStatus row.
     */
    public record VettingItem(AttentionType type, Long id, FleetShip ship, InspectionState state, LocalDate validUntil,
                              long daysLeft, Integer score, int level) implements AttentionItem {

        public Set<Long> shipIds() {
            return Set.of(ship.id());
        }

        public long sortKey() {
            if (type == AttentionType.VETTING_SCORE) {
                return score;
            }
            return validUntil == null ? Long.MIN_VALUE : validUntil.toEpochDay();
        }

        public Alert alert() {
            return Alert.of(type == AttentionType.VETTING_SCORE ? AlertKind.VETTING_SCORE : AlertKind.VETTING_INSPECTION,
                    ship.id(), level);
        }

        public LocalDate dueDate() {
            return type == AttentionType.VETTING_INSPECTION ? validUntil : null;
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

    /**
     * An open request for quotes (P44): RFQ_READY with quotes from at least two suppliers (the oldest request first),
     * or RFQ_PAST_REPLY_BY when the reply-by date has passed with fewer (the longest overdue first). quoted: suppliers
     * who quoted; waiting: suppliers not yet answered.
     */
    public record RfqItem(AttentionType type, Long id, Long orderId, FleetShip ship, LocalDate replyBy, Instant sentAt,
                          long quoted, long waiting) implements AttentionItem {

        public Set<Long> shipIds() {
            return Set.of(ship.id());
        }

        public long sortKey() {
            if (type == AttentionType.RFQ_PAST_REPLY_BY) {
                return replyBy.toEpochDay();
            }
            return sentAt == null ? Long.MIN_VALUE : sentAt.getEpochSecond();
        }

        public LocalDate dueDate() {
            return type == AttentionType.RFQ_PAST_REPLY_BY ? replyBy : null;
        }

        /** The RFQ's page, where the comparison and the quotes are. */
        public String url() {
            return "/office/orders/" + orderId + "/rfqs/" + id;
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

        public LocalDate dueDate() {
            return LocalDate.ofInstant(expiresAt, ZoneOffset.UTC);
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

    /** By the date that drives the item, soonest first; items without one last, in urgency order. */
    static final Comparator<AttentionItem> BY_DATE = Comparator
            .comparing(AttentionItem::dueDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(URGENCY);

    // ---------------------------------------------------------------------------------------------------------------
    // The page
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * One active ship in the fleet card (P43): its RightShip standing (the Safety Score badge) and its open items,
     * not snoozed. certificateIssues and ordersNotExported are the ship page's rule (ShipPageFlowTest).
     */
    public record FleetRow(FleetShip ship, long crew, RightShipView view, long certificateIssues, long notAcknowledged,
                           long ordersNotExported, long openItems) {
    }

    /** A kind of open item with its number (not snoozed), for the chips and the By category card. */
    public record GroupCount(Group group, long count) {
    }

    /** A coming date of the Next 30 days card: what, which vessel, when, and the page it opens. kind: a message
     *  key's suffix (dashboard.upcoming.<kind>). */
    public record Upcoming(LocalDate date, String kind, String title, FleetShip ship, String url) {
    }

    /**
     * The Overview.
     * fleet          active ships, those with open items first, then by name (the Fleet card shows FLEET_SHOWN)
     * crewOnBoard    enabled crew on the active ships
     * openItems      every open item of the fleet, not snoozed, whatever the filter (the Items tile)
     * snoozedItems   the items a snooze holds (the tile's detail line)
     * ordersWaiting  orders sent and not yet confirmed (the Orders tile)
     * items          one page of the open items the filter keeps, in the chosen order (ListQuery: page, sort)
     * groups         the number of open items per kind (unfiltered), every kind, zero included
     * group, ship    the filter (/office?type=...&shipId=...), each null when not set
     * upcoming       the Next 30 days card's dates, soonest first
     * recent         the newest audit entries
     * checkedAt      when this was loaded
     */
    public record Dashboard(List<FleetRow> fleet, long crewOnBoard, long openItems, long snoozedItems,
                            long ordersWaiting, Page<AttentionItem> items, List<GroupCount> groups, Group group,
                            FleetShip ship, List<Upcoming> upcoming, List<AuditEntry> recent, Instant checkedAt) {

        /** A filter chip's URL: this page with the kind (null for All), keeping the vessel filter and the sort. */
        public String chipUrl(Group kind, boolean byDate) {
            List<String> params = new ArrayList<>();
            if (kind != null) {
                params.add("type=" + kind.param());
            }
            if (ship != null) {
                params.add("shipId=" + ship.id());
            }
            if (byDate) {
                params.add("sort=" + SORT_DATE);
            }
            return params.isEmpty() ? "/office" : "/office?" + String.join("&", params);
        }

        public boolean filtered() {
            return group != null || ship != null;
        }

        public long countOf(Group group) {
            return groups.stream().filter(g -> g.group() == group).mapToLong(GroupCount::count).sum();
        }

        /** As countOf, by the group's name: for the templates, where a static reference is not allowed. */
        public long countOf(String group) {
            return countOf(Group.valueOf(group));
        }

        /** The vessels with open items. */
        public long vesselsWithOpenItems() {
            return fleet.stream().filter(row -> row.openItems() > 0).count();
        }

        /** The Fleet card's rows: at most FLEET_SHOWN. */
        public List<FleetRow> fleetShown() {
            return fleet.size() <= FLEET_SHOWN ? fleet : fleet.subList(0, FLEET_SHOWN);
        }
    }

    /** The unfiltered Overview, page 1, most urgent first. */
    @Transactional
    public Dashboard load() {
        return load(null, null, ListQuery.of("/office", null, com.nereonix.nereonet_mvp.core.list.ListSort.asc(SORT_URGENCY),
                com.nereonix.nereonet_mvp.core.list.ListSort.asc(SORT_DATE)));
    }

    /**
     * The Overview, its open items filtered to one kind (group) and / or one active ship (shipId); null keeps all.
     * An unknown or inactive shipId filters nothing. list: the page and sort of the "Needs attention" list.
     * Not read-only: the snoozes that no longer apply are ended here (SnoozeService.sync).
     */
    @Transactional
    public Dashboard load(Group group, Long shipId, ListQuery list) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);

        List<FleetShip> ships = queries.activeShips();
        Map<Long, FleetShip> shipsById = ships.stream()
                .collect(Collectors.toMap(FleetShip::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        List<AttentionItem> all = new ArrayList<>();
        // P43: the certificates up to 90 days ahead, every ship: the Certificates list can snooze any of them, so
        // the snooze sync below needs all their alerts (else a snooze of one 45 days ahead would end as "resolved").
        // Only those of active ships within OPEN_ITEM_CERTIFICATE_DAYS are open items.
        List<Alert> otherAlerts = new ArrayList<>();
        for (DashboardQueries.CertificateRow c : queries.certificatesExpiringUntil(today.plusDays(SYNC_CERTIFICATE_DAYS))) {
            long daysLeft = ChronoUnit.DAYS.between(today, c.expiresOn());
            FleetShip vessel = c.shipId() == null ? null : shipsById.get(c.shipId());
            if (daysLeft > OPEN_ITEM_CERTIFICATE_DAYS || vessel == null) {
                otherAlerts.add(Alert.of(daysLeft < 0 ? AlertKind.CERTIFICATE_EXPIRED : AlertKind.CERTIFICATE_EXPIRING,
                        c.id(), AlertLevels.ofDaysLeft(daysLeft)));
                continue;
            }
            AttentionType type = daysLeft < 0 ? AttentionType.CERTIFICATE_EXPIRED : AttentionType.CERTIFICATE_EXPIRING;
            all.add(new CertificateItem(type, c.id(), c.userId(), c.type(), c.expiresOn(), daysLeft, vessel,
                    c.fullName(), c.rank()));
        }
        // P42: required documents, worked out on this read; ranked right after expired certificates.
        for (Compliance c : complianceService.onBoard()) {
            FleetShip vessel = shipsById.get(c.user().getShip().getId());
            if (c.missing() > 0 && vessel != null) {
                all.add(new DocumentsItem(c.user().getId(), c.user().getFullName(), c.user().getRank(), vessel,
                        c.missing(), c.required(), c.missingDocuments().stream().map(RequiredDocument::type).toList()));
            }
        }
        // P40, P43: every vessel's current RightShip row, for the Fleet card's badge and the vetting items.
        Map<Long, RightShipView> views = new LinkedHashMap<>();
        for (ShipRightShipStatus row : rightShipRepository.findAllCurrent()) {
            FleetShip vessel = shipsById.get(row.getShip().getId());
            if (vessel == null) {
                continue;
            }
            RightShipView view = new RightShipView(row, today);
            views.put(vessel.id(), view);
            InspectionState state = view.inspection();
            if (state.needsAttention()) {
                all.add(new VettingItem(AttentionType.VETTING_INSPECTION, row.getId(), vessel, state,
                        view.inspectionValidUntil(), view.inspectionDaysLeft(), view.score(), view.inspectionLevel()));
            }
            if (view.lowScore()) {
                all.add(new VettingItem(AttentionType.VETTING_SCORE, row.getId(), vessel, state, view.inspectionValidUntil(),
                        0, view.score(), view.score()));
            }
        }
        SireService.Attention sire = sireService.attention(today);
        for (SireView s : sire.inspections()) {
            FleetShip vessel = shipsById.get(s.inspection().getShip().getId());
            if (s.commentsUrgent()) {
                all.add(new SireItem(AttentionType.SIRE_COMMENTS, s.inspection().getId(), vessel, s.commentsDueOn(),
                        s.commentsDaysLeft(), s.reportState(), null, null, s.commentsLevel()));
            }
            if (s.reportState().needsAttention()) {
                all.add(new SireItem(AttentionType.SIRE_REPORT, s.inspection().getId(), vessel, s.currentUntil(),
                        s.currentDaysLeft(), s.reportState(), null, null, s.reportLevel()));
            }
        }
        for (SireObservation o : sire.overdueObservations()) {
            all.add(new SireItem(AttentionType.SIRE_OBSERVATION, o.getId(),
                    shipsById.get(o.getInspection().getShip().getId()), o.getTargetDate(),
                    ChronoUnit.DAYS.between(today, o.getTargetDate()), null, o.getQuestionNumber(), o.getType(),
                    AlertLevels.EXPIRED));
        }
        List<DashboardQueries.OrderRow> orders = queries.orders(today, today.plusDays(UPCOMING_DAYS));
        for (DashboardQueries.OrderRow o : orders) {
            if (o.notExported()) {
                all.add(new OrderItem(o.id(), shipsById.get(o.shipId()), o.sentBy(), o.rank(), o.sentAt(), o.lines()));
            }
        }
        // P44: open requests for quotes with two quotes to compare, or past their reply-by date with fewer.
        for (DashboardQueries.RfqRow r : queries.openRfqs()) {
            AttentionType type = r.quoted() >= RfqComparison.MIN_QUOTES ? AttentionType.RFQ_READY
                    : r.replyBy().isBefore(today) ? AttentionType.RFQ_PAST_REPLY_BY : null;
            if (type != null) {
                all.add(new RfqItem(type, r.id(), r.orderId(), shipsById.get(r.shipId()), r.replyBy(), r.sentAt(),
                        r.quoted(), r.waiting()));
            }
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

        // P43: the snoozes, ended where they no longer apply; the items a snooze holds are left out of everything.
        List<Alert> alerts = new ArrayList<>(otherAlerts);
        all.stream().map(AttentionItem::alert).filter(a -> a != null).forEach(alerts::add);
        Map<AlertKey, AlertSnooze> snoozes = snoozeService.sync(alerts, today);
        List<AttentionItem> open = new ArrayList<>();
        long snoozed = 0;
        for (AttentionItem item : all) {
            if (item.alert() != null && SnoozeService.holding(snoozes, item.alert()) != null) {
                snoozed++;
            } else {
                open.add(item);
            }
        }
        open.sort(URGENCY);

        List<FleetRow> fleet = ships.stream()
                .map(ship -> fleetRow(ship, views.getOrDefault(ship.id(), new RightShipView(null, today)), open))
                .sorted(Comparator.comparing(row -> row.openItems() == 0)) // stable: keeps the name order
                .toList();

        Map<Group, Long> perGroup = new EnumMap<>(Group.class);
        for (Group g : Group.values()) {
            perGroup.put(g, 0L);
        }
        open.forEach(item -> perGroup.merge(item.type().group(), 1L, Long::sum));
        List<GroupCount> groups = perGroup.entrySet().stream()
                .map(e -> new GroupCount(e.getKey(), e.getValue())).toList();

        FleetShip ship = shipId == null ? null : shipsById.get(shipId);
        List<AttentionItem> filtered = new ArrayList<>(open.stream()
                .filter(item -> group == null || item.type().group() == group)
                .filter(item -> ship == null || item.shipIds().contains(ship.id()))
                .toList());
        if (SORT_DATE.equals(list.getSort())) {
            filtered.sort(list.isDescending() ? BY_DATE.reversed() : BY_DATE);
        } else if (list.isDescending()) {
            filtered.sort(URGENCY.reversed());
        }
        Page<AttentionItem> page = page(filtered, list);

        long ordersWaiting = orders.stream().filter(o -> o.status() == ShipOrderStatus.SENT).count();
        List<AuditEntry> recent = auditRepository.findNewest(PageRequest.of(0, RECENT_SHOWN));

        return new Dashboard(fleet, ships.stream().mapToLong(FleetShip::crew).sum(), open.size(), snoozed,
                ordersWaiting, page, groups, group, ship, upcoming(open, orders, shipsById, today), recent, now);
    }

    private static Page<AttentionItem> page(List<AttentionItem> items, ListQuery list) {
        int page = ListQueries.clamp(list, items.size());
        int from = Math.min(page * list.getSize(), items.size());
        int to = Math.min(from + list.getSize(), items.size());
        return new PageImpl<>(List.copyOf(items.subList(from, to)), PageRequest.of(page, list.getSize()), items.size());
    }

    /**
     * The Next 30 days card (P43): the dates ahead among the open items (certificate expiries, RightShip inspections
     * ending, SIRE 2.0 reports ending and comments due) and the confirmed deliveries, soonest first, at most
     * UPCOMING_SHOWN. Nothing that has passed, nothing snoozed.
     */
    private static List<Upcoming> upcoming(List<AttentionItem> open, List<DashboardQueries.OrderRow> orders,
                                           Map<Long, FleetShip> ships, LocalDate today) {
        LocalDate until = today.plusDays(UPCOMING_DAYS);
        List<Upcoming> dates = new ArrayList<>();
        for (AttentionItem item : open) {
            LocalDate date = item.dueDate();
            if (date == null || date.isBefore(today) || date.isAfter(until)) {
                continue;
            }
            switch (item) {
                case CertificateItem c -> dates.add(new Upcoming(date, "certificate", c.certificateType(), c.ship(),
                        "/office/crew/" + c.userId() + "/certificates/" + c.id() + "/edit?returnTo=/office"));
                case VettingItem v -> dates.add(new Upcoming(date, "inspection", null, v.ship(),
                        "/office/vetting/" + v.ship().id() + "/rightship"));
                case SireItem s -> dates.add(new Upcoming(date, s.type() == AttentionType.SIRE_COMMENTS ? "comments" : "report",
                        null, s.ship(), "/office/vetting/" + s.ship().id() + "/sire"));
                default -> { }
            }
        }
        for (DashboardQueries.OrderRow o : orders) {
            if (o.status() == ShipOrderStatus.CONFIRMED && o.deliveryDate() != null && !o.deliveryDate().isBefore(today)
                    && !o.deliveryDate().isAfter(until)) {
                dates.add(new Upcoming(o.deliveryDate(), "delivery", "#" + o.id(), ships.get(o.shipId()),
                        "/office/orders/" + o.id()));
            }
        }
        dates.sort(Comparator.comparing(Upcoming::date).thenComparing(Upcoming::kind));
        return dates.size() <= UPCOMING_SHOWN ? dates : List.copyOf(dates.subList(0, UPCOMING_SHOWN));
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

    private static FleetRow fleetRow(FleetShip ship, RightShipView view, List<AttentionItem> items) {
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
                case QUOTES, INVITATIONS, VETTING, DOCUMENTS -> { }
            }
        }
        return new FleetRow(ship, ship.crew(), view, certificates, acknowledgements, orders, open);
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
