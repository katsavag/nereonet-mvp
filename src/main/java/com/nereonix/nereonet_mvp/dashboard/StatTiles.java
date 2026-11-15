package com.nereonix.nereonet_mvp.dashboard;

import java.util.List;

import com.nereonix.nereonet_mvp.core.order.ShipOrderStatus;
import com.nereonix.nereonet_mvp.dashboard.DashboardQueries.FleetShip;

/**
 * The numbers of the Overview's stat tiles and their detail lines (Active vessels counts the Fleet card's rows).
 * crewOnBoard: enabled crew of the active ships; openItems and snoozedItems: the Needs attention card's items and
 * those a snooze holds; ordersWaiting: orders sent and not yet scheduled; ordersNotExported: the open Not exported
 * items (shortages and invoices are not counted). No query of its own.
 */
record StatTiles(long crewOnBoard, long openItems, long snoozedItems, long ordersWaiting, long ordersNotExported) {

    static StatTiles of(List<FleetShip> ships, NeedsAttention.OpenItems items) {
        return new StatTiles(
                ships.stream().mapToLong(FleetShip::crew).sum(),
                items.open().size(),
                items.snoozed(),
                items.orders().stream().filter(o -> o.status() == ShipOrderStatus.SENT).count(),
                items.open().stream().filter(item -> item.type() == AttentionType.ORDER_NOT_EXPORTED).count());
    }
}
