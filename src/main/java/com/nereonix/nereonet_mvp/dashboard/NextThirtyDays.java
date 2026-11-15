package com.nereonix.nereonet_mvp.dashboard;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.dashboard.AttentionItem.CertificateItem;
import com.nereonix.nereonet_mvp.dashboard.AttentionItem.SireItem;
import com.nereonix.nereonet_mvp.dashboard.AttentionItem.VettingItem;
import com.nereonix.nereonet_mvp.dashboard.DashboardQueries.FleetShip;
import com.nereonix.nereonet_mvp.dashboard.DashboardService.Upcoming;

/**
 * The Overview's "Next 30 days" card: the dates ahead among the open items (certificate expiries, RightShip
 * inspections ending, SIRE 2.0 reports ending and comments due) and the confirmed deliveries, soonest first, at most
 * SHOWN. Nothing that has passed, nothing snoozed. No query of its own.
 */
final class NextThirtyDays {

    /** How far ahead the card looks, and how many dates it shows at most. */
    static final int DAYS = 30;
    static final int SHOWN = 8;

    private NextThirtyDays() {
    }

    static List<Upcoming> dates(List<AttentionItem> open, List<DashboardQueries.OrderRow> orders,
                                Map<Long, FleetShip> ships, LocalDate today) {
        LocalDate until = today.plusDays(DAYS);
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
        return dates.size() <= SHOWN ? dates : List.copyOf(dates.subList(0, SHOWN));
    }
}
