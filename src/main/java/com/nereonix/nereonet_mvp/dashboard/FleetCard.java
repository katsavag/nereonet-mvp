package com.nereonix.nereonet_mvp.dashboard;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.nereonix.nereonet_mvp.core.vetting.RightShipView;
import com.nereonix.nereonet_mvp.dashboard.DashboardQueries.FleetShip;
import com.nereonix.nereonet_mvp.dashboard.DashboardService.FleetRow;

/**
 * The Overview's Fleet card: one row per active ship with its RightShip standing and its open items, those with open
 * items first, then by name; the card shows the first SHOWN. No query of its own.
 */
final class FleetCard {

    /** The card shows at most this many vessels. */
    static final int SHOWN = 8;

    private FleetCard() {
    }

    /** ships in name order; rightShip: the standings by ship id (a ship without one gets an empty standing). */
    static List<FleetRow> rows(List<FleetShip> ships, Map<Long, RightShipView> rightShip, List<AttentionItem> open,
                               LocalDate today) {
        return ships.stream()
                .map(ship -> row(ship, rightShip.getOrDefault(ship.id(), new RightShipView(null, today)), open))
                .sorted(Comparator.comparing(row -> row.openItems() == 0)) // stable: keeps the name order
                .toList();
    }

    private static FleetRow row(FleetShip ship, RightShipView view, List<AttentionItem> items) {
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
}
