package com.nereonix.nereonet_mvp.core.stock;

// What changed a stock quantity (P52). Labels: stores.movement.<NAME> in messages.properties.
public enum StockMovementType {

    // The Chief cook counted the item and set what is on board.
    COUNT,
    // A delivery was received on board; StockMovement.order is the ship order it belonged to.
    RECEIPT;

    public String labelKey() {
        return "stores.movement." + name();
    }
}
