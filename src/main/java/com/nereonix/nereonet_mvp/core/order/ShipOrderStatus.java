package com.nereonix.nereonet_mvp.core.order;

// The life of a ship's consolidated order (P34). Only OPEN is used so far: approved requests are added to it.
public enum ShipOrderStatus {
    // Collecting approved requests. A ship has at most one (ShipOrderService keeps it so).
    OPEN,
    // Sent by the ship to the office.
    SENT,
    // Confirmed by the office: port and delivery date.
    CONFIRMED,
    // Delivered or otherwise done.
    CLOSED
}
