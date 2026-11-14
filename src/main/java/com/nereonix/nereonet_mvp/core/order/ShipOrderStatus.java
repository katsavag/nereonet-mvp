package com.nereonix.nereonet_mvp.core.order;

// The life of a ship's consolidated order (P34). The Master sends an OPEN order (P35); the office schedules the
// delivery; the Master confirms receipt on board (P51). Labels: order.status.<NAME> (crew), officeorder.status.<NAME>.
public enum ShipOrderStatus {
    // Collecting approved requests. A ship has at most one (ShipOrderService keeps it so).
    OPEN,
    // Sent by the ship to the office.
    SENT,
    // The office scheduled the delivery: port and date (P51; the office's "Delivery pending").
    CONFIRMED,
    // Received on board: the Master confirmed receipt with the delivery receipt (P51).
    CLOSED;

    public String labelKey() {
        return "order.status." + name();
    }
}
