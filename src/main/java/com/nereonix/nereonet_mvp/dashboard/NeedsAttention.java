package com.nereonix.nereonet_mvp.dashboard;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.nereonix.nereonet_mvp.core.alert.Alert;
import com.nereonix.nereonet_mvp.core.alert.AlertKey;
import com.nereonix.nereonet_mvp.core.alert.AlertKind;
import com.nereonix.nereonet_mvp.core.alert.AlertLevels;
import com.nereonix.nereonet_mvp.core.alert.AlertSnooze;
import com.nereonix.nereonet_mvp.core.alert.SnoozeService;
import com.nereonix.nereonet_mvp.core.list.ListQueries;
import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.rfq.RfqComparison;
import com.nereonix.nereonet_mvp.core.vetting.InspectionState;
import com.nereonix.nereonet_mvp.core.vetting.RightShipView;
import com.nereonix.nereonet_mvp.core.vetting.ShipRightShipStatus;
import com.nereonix.nereonet_mvp.core.vetting.ShipRightShipStatusRepository;
import com.nereonix.nereonet_mvp.core.vetting.SireObservation;
import com.nereonix.nereonet_mvp.core.vetting.SireService;
import com.nereonix.nereonet_mvp.core.vetting.SireView;
import com.nereonix.nereonet_mvp.dashboard.AttentionItem.AnnouncementItem;
import com.nereonix.nereonet_mvp.dashboard.AttentionItem.CertificateItem;
import com.nereonix.nereonet_mvp.dashboard.AttentionItem.DocumentsItem;
import com.nereonix.nereonet_mvp.dashboard.AttentionItem.InvitationItem;
import com.nereonix.nereonet_mvp.dashboard.AttentionItem.InvoiceItem;
import com.nereonix.nereonet_mvp.dashboard.AttentionItem.LibraryAttentionItem;
import com.nereonix.nereonet_mvp.dashboard.AttentionItem.OrderItem;
import com.nereonix.nereonet_mvp.dashboard.AttentionItem.RfqItem;
import com.nereonix.nereonet_mvp.dashboard.AttentionItem.ShortageItem;
import com.nereonix.nereonet_mvp.dashboard.AttentionItem.SireItem;
import com.nereonix.nereonet_mvp.dashboard.AttentionItem.VettingItem;
import com.nereonix.nereonet_mvp.dashboard.AttentionType.Group;
import com.nereonix.nereonet_mvp.dashboard.DashboardQueries.FleetShip;
import com.nereonix.nereonet_mvp.dashboard.DashboardService.GroupCount;
import com.nereonix.nereonet_mvp.requirement.Compliance;
import com.nereonix.nereonet_mvp.requirement.ComplianceService;
import com.nereonix.nereonet_mvp.requirement.RequiredDocument;

/**
 * The Overview's "Needs attention" card: every open item of the fleet, one method per kind, less the items a snooze
 * holds; and the card's filter chips, sort and page.
 *
 * find() runs eleven queries: the certificates, the required documents' rules (two more, the crew on board and their
 * certificates, when any rule is set: ComplianceService.onBoard), the RightShip rows, SIRE 2.0 (two), the orders,
 * the open requests for quotes, the invitations, the announcements, the library items, and the snoozes
 * (SnoozeService.sync, which also ends the snoozes that no longer apply: a write).
 */
@Component
public class NeedsAttention {

    /** A certificate is an open item when it has expired or expires within this many days (UTC). */
    public static final int OPEN_ITEM_CERTIFICATE_DAYS = 30;
    /** The snooze sync looks this far ahead for certificates: as far as the Certificates list alerts. */
    public static final int SYNC_CERTIFICATE_DAYS = 90;

    /** A pending crew invitation is an open item this long after it was (last) sent, or once it has expired. */
    public static final Duration INVITATION_PENDING_LIMIT = Duration.ofHours(48);

    /** An announcement or a Required library item is an open item this long after it was sent / published. */
    public static final Duration ACKNOWLEDGEMENT_LIMIT = Duration.ofDays(7);

    /** The sort keys of the list: most urgent first (the default), or by date. */
    public static final String SORT_URGENCY = "urgency";
    public static final String SORT_DATE = "date";

    /**
     * What find() found. open: the open items not snoozed, most urgent first; snoozed: how many a snooze holds;
     * rightShip: each active vessel's RightShip standing (the Fleet card's badge); orders: the orders the office works
     * on (the Orders tile and the Next 30 days card read them too).
     */
    public record OpenItems(List<AttentionItem> open, long snoozed, Map<Long, RightShipView> rightShip,
                            List<DashboardQueries.OrderRow> orders) {
    }

    private final DashboardQueries queries;
    private final ComplianceService complianceService;
    private final ShipRightShipStatusRepository rightShipRepository;
    private final SireService sireService;
    private final SnoozeService snoozeService;
    // The same setting InvitationService uses for an invitation's expiry date.
    private final int invitationValidDays;

    public NeedsAttention(DashboardQueries queries, ComplianceService complianceService,
                          ShipRightShipStatusRepository rightShipRepository, SireService sireService,
                          SnoozeService snoozeService, @Value("${app.invitation.valid-days:7}") int invitationValidDays) {
        this.queries = queries;
        this.complianceService = complianceService;
        this.rightShipRepository = rightShipRepository;
        this.sireService = sireService;
        this.snoozeService = snoozeService;
        this.invitationValidDays = invitationValidDays;
    }

    /** The open items of the active ships (ships, by id), and the snoozes ended where they no longer apply. */
    OpenItems find(Map<Long, FleetShip> ships, Instant now, LocalDate today) {
        List<AttentionItem> all = new ArrayList<>();
        // Alerts that are not open items (further ahead, or of inactive ships) but that a snooze may hold.
        List<Alert> otherAlerts = new ArrayList<>();
        certificates(ships, today, all, otherAlerts);
        documents(ships, all);
        Map<Long, RightShipView> rightShip = rightShip(ships, today, all);
        sire(ships, today, all);
        List<DashboardQueries.OrderRow> orders = queries.orders();
        orders(orders, ships, all);
        rfqs(ships, today, all);
        invitations(ships, now, all);
        all.addAll(announcements(queries.announcementsSentBefore(now.minus(ACKNOWLEDGEMENT_LIMIT)), ships));
        library(ships, now, all);

        // The items a snooze holds are left out of everything but the Items tile's detail line.
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
        open.sort(AttentionItem.URGENCY);
        return new OpenItems(open, snoozed, rightShip, orders);
    }

    /**
     * Certificates expired or expiring within OPEN_ITEM_CERTIFICATE_DAYS, on active ships. The query reads them up
     * to SYNC_CERTIFICATE_DAYS ahead for every ship, since the Certificates list can snooze any of them: the others
     * go to otherAlerts, so the snooze sync does not end their snoozes as resolved.
     */
    private void certificates(Map<Long, FleetShip> ships, LocalDate today, List<AttentionItem> items,
                              List<Alert> otherAlerts) {
        for (DashboardQueries.CertificateRow c : queries.certificatesExpiringUntil(today.plusDays(SYNC_CERTIFICATE_DAYS))) {
            long daysLeft = ChronoUnit.DAYS.between(today, c.expiresOn());
            FleetShip vessel = c.shipId() == null ? null : ships.get(c.shipId());
            if (daysLeft > OPEN_ITEM_CERTIFICATE_DAYS || vessel == null) {
                otherAlerts.add(Alert.of(daysLeft < 0 ? AlertKind.CERTIFICATE_EXPIRED : AlertKind.CERTIFICATE_EXPIRING,
                        c.id(), AlertLevels.ofDaysLeft(daysLeft)));
                continue;
            }
            AttentionType type = daysLeft < 0 ? AttentionType.CERTIFICATE_EXPIRED : AttentionType.CERTIFICATE_EXPIRING;
            items.add(new CertificateItem(type, c.id(), c.userId(), c.type(), c.expiresOn(), daysLeft, vessel,
                    c.fullName(), c.rank()));
        }
    }

    /** Seafarers on board missing required documents, worked out on this read. */
    private void documents(Map<Long, FleetShip> ships, List<AttentionItem> items) {
        for (Compliance c : complianceService.onBoard()) {
            FleetShip vessel = ships.get(c.user().getShip().getId());
            if (c.missing() > 0 && vessel != null) {
                items.add(new DocumentsItem(c.user().getId(), c.user().getFullName(), c.user().getRank(), vessel,
                        c.missing(), c.required(), c.missingDocuments().stream().map(RequiredDocument::type).toList()));
            }
        }
    }

    /** Every active vessel's current RightShip row: its inspection and Safety Score items; returns the standings. */
    private Map<Long, RightShipView> rightShip(Map<Long, FleetShip> ships, LocalDate today, List<AttentionItem> items) {
        Map<Long, RightShipView> views = new LinkedHashMap<>();
        for (ShipRightShipStatus row : rightShipRepository.findAllCurrent()) {
            FleetShip vessel = ships.get(row.getShip().getId());
            if (vessel == null) {
                continue;
            }
            RightShipView view = new RightShipView(row, today);
            views.put(vessel.id(), view);
            InspectionState state = view.inspection();
            if (state.needsAttention()) {
                items.add(new VettingItem(AttentionType.VETTING_INSPECTION, row.getId(), vessel, state,
                        view.inspectionValidUntil(), view.inspectionDaysLeft(), view.score(), view.inspectionLevel()));
            }
            if (view.lowScore()) {
                items.add(new VettingItem(AttentionType.VETTING_SCORE, row.getId(), vessel, state, view.inspectionValidUntil(),
                        0, view.score(), view.score()));
            }
        }
        return views;
    }

    /** SIRE 2.0: operator comments due, reports ending or no longer current, observations past their target. */
    private void sire(Map<Long, FleetShip> ships, LocalDate today, List<AttentionItem> items) {
        SireService.Attention sire = sireService.attention(today);
        for (SireView s : sire.inspections()) {
            FleetShip vessel = ships.get(s.inspection().getShip().getId());
            if (s.commentsUrgent()) {
                items.add(new SireItem(AttentionType.SIRE_COMMENTS, s.inspection().getId(), vessel, s.commentsDueOn(),
                        s.commentsDaysLeft(), s.reportState(), null, null, s.commentsLevel()));
            }
            if (s.reportState().needsAttention()) {
                items.add(new SireItem(AttentionType.SIRE_REPORT, s.inspection().getId(), vessel, s.currentUntil(),
                        s.currentDaysLeft(), s.reportState(), null, null, s.reportLevel()));
            }
        }
        for (SireObservation o : sire.overdueObservations()) {
            items.add(new SireItem(AttentionType.SIRE_OBSERVATION, o.getId(),
                    ships.get(o.getInspection().getShip().getId()), o.getTargetDate(),
                    ChronoUnit.DAYS.between(today, o.getTargetDate()), null, o.getQuestionNumber(), o.getType(),
                    AlertLevels.EXPIRED));
        }
    }

    /**
     * Orders not exported; received with a read invoice, until the office marks it matched; received short without
     * a read invoice, until the office marks the shortage handled.
     */
    private static void orders(List<DashboardQueries.OrderRow> orders, Map<Long, FleetShip> ships,
                               List<AttentionItem> items) {
        for (DashboardQueries.OrderRow o : orders) {
            if (o.notExported()) {
                items.add(new OrderItem(o.id(), ships.get(o.shipId()), o.sentBy(), o.rank(), o.sentAt(), o.lines()));
            }
            if (o.invoiceToMatch()) {
                items.add(new InvoiceItem(o.id(), ships.get(o.shipId()), o.receivedAt(), o.invoiceDiffLines()));
            }
            if (o.shortageOpen()) {
                items.add(new ShortageItem(o.id(), ships.get(o.shipId()), o.receivedAt(), o.shortLines()));
            }
        }
    }

    /** Open requests for quotes with two quotes to compare, or past their reply-by date with fewer. */
    private void rfqs(Map<Long, FleetShip> ships, LocalDate today, List<AttentionItem> items) {
        for (DashboardQueries.RfqRow r : queries.openRfqs()) {
            AttentionType type = r.quoted() >= RfqComparison.MIN_QUOTES ? AttentionType.RFQ_READY
                    : r.replyBy().isBefore(today) ? AttentionType.RFQ_PAST_REPLY_BY : null;
            if (type != null) {
                items.add(new RfqItem(type, r.id(), r.orderId(), ships.get(r.shipId()), r.replyBy(), r.sentAt(),
                        r.quoted(), r.waiting()));
            }
        }
    }

    /**
     * Pending crew invitations sent (last) more than INVITATION_PENDING_LIMIT ago. expiresAt is (last) sent + the
     * validity, so that is a bound on expiresAt; it includes the expired ones, sent at least the validity ago.
     */
    private void invitations(Map<Long, FleetShip> ships, Instant now, List<AttentionItem> items) {
        Duration validity = Duration.ofDays(invitationValidDays);
        Instant expiresBefore = now.plus(validity).minus(INVITATION_PENDING_LIMIT);
        for (DashboardQueries.InvitationRow i : queries.pendingInvitationsExpiringBefore(expiresBefore)) {
            items.add(new InvitationItem(i.id(), ships.get(i.shipId()), i.fullName(), i.rank(),
                    i.expiresAt().minus(validity), i.expiresAt(), i.expiresAt().isBefore(now)));
        }
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

    /** Required library items whose current version not everyone on board has acknowledged. */
    private void library(Map<Long, FleetShip> ships, Instant now, List<AttentionItem> items) {
        for (DashboardQueries.LibraryRow l : queries.requiredItemsNotAcknowledged(now.minus(ACKNOWLEDGEMENT_LIMIT))) {
            items.add(new LibraryAttentionItem(l.id(), l.title(), ships.get(l.shipId()), l.versionLabel(),
                    l.publishedAt(), l.onBoard(), l.acknowledged()));
        }
    }

    /** The number of open items per kind, every kind, zero included: the filter chips. */
    static List<GroupCount> groups(List<AttentionItem> open) {
        Map<Group, Long> perGroup = new EnumMap<>(Group.class);
        for (Group g : Group.values()) {
            perGroup.put(g, 0L);
        }
        open.forEach(item -> perGroup.merge(item.type().group(), 1L, Long::sum));
        return perGroup.entrySet().stream().map(e -> new GroupCount(e.getKey(), e.getValue())).toList();
    }

    /** One page of the open items of one kind (group) and / or one ship (each null for all), in the list's order. */
    static Page<AttentionItem> page(List<AttentionItem> open, Group group, FleetShip ship, ListQuery list) {
        List<AttentionItem> filtered = new ArrayList<>(open.stream()
                .filter(item -> group == null || item.type().group() == group)
                .filter(item -> ship == null || item.shipIds().contains(ship.id()))
                .toList());
        if (SORT_DATE.equals(list.getSort())) {
            filtered.sort(list.isDescending() ? AttentionItem.BY_DATE.reversed() : AttentionItem.BY_DATE);
        } else if (list.isDescending()) {
            filtered.sort(AttentionItem.URGENCY.reversed());
        }
        int page = ListQueries.clamp(list, filtered.size());
        int from = Math.min(page * list.getSize(), filtered.size());
        int to = Math.min(from + list.getSize(), filtered.size());
        return new PageImpl<>(List.copyOf(filtered.subList(from, to)), PageRequest.of(page, list.getSize()),
                filtered.size());
    }
}
