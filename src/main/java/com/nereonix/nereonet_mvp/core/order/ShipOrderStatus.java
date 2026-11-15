package com.nereonix.nereonet_mvp.core.order;

import java.util.Locale;

// The life of a ship's consolidated order (P34). The Master sends an OPEN order (P35); the office schedules the
// delivery; the Master confirms receipt on board (P51). Labels: order.status.<NAME>, the same words on the ship's
// pages and the office's (P65): Collecting, Pending, Delivery scheduled, Received.
public enum ShipOrderStatus {
    // Collecting approved requests. A ship has at most one (ShipOrderService keeps it so).
    OPEN,
    // Sent by the ship to the office: "Pending", waiting for the office to schedule the delivery.
    SENT,
    // The office scheduled the delivery: port and date (P51; "Delivery scheduled").
    CONFIRMED,
    // Received on board: the Master confirmed receipt with the delivery receipt (P51).
    CLOSED;

    public String labelKey() {
        return "order.status." + name();
    }

    /** The value of the office list's status filter (/office/orders?status=...): sent, delivery_scheduled (P65), closed. */
    public String filterParam() {
        return this == CONFIRMED ? "delivery_scheduled" : name().toLowerCase(Locale.ROOT);
    }
}
