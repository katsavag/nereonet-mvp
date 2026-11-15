package com.nereonix.nereonet_mvp.core.order;

import java.util.Locale;

// The life of a ship's consolidated order. The Master sends an OPEN order; the office schedules the
// delivery; the Master confirms receipt on board. Labels: order.status.<NAME>, the same words on the ship's
// pages and the office's: Collecting, Pending, Delivery scheduled, Received.
public enum ShipOrderStatus {
    // Collecting approved requests. A ship has at most one (ShipOrderService keeps it so).
    OPEN,
    // Sent by the ship to the office: "Pending", waiting for the office to schedule the delivery.
    SENT,
    // The office scheduled the delivery: port and date ("Delivery scheduled").
    CONFIRMED,
    // Received on board: the Master confirmed receipt with the delivery receipt.
    CLOSED;

    public String labelKey() {
        return "order.status." + name();
    }

    /** The value of the office list's status filter (/office/orders?status=...): sent, delivery_scheduled, closed. */
    public String filterParam() {
        return this == CONFIRMED ? "delivery_scheduled" : name().toLowerCase(Locale.ROOT);
    }
}
