package com.nereonix.nereonet_mvp.core.order;

// The life of a ship's consolidated order (P34). The Master sends an OPEN order (P35); the office confirms and closes
// it. Labels: order.status.<NAME>.
public enum ShipOrderStatus {
    // Collecting approved requests. A ship has at most one (ShipOrderService keeps it so).
    OPEN,
    // Sent by the ship to the office.
    SENT,
    // Confirmed by the office: port and delivery date.
    CONFIRMED,
    // Delivered or otherwise done.
    CLOSED;

    public String labelKey() {
        return "order.status." + name();
    }
}
