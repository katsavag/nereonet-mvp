package com.nereonix.nereonet_mvp.core.stock;

import java.util.Arrays;
import java.util.Locale;

// How much of a provisions item is on board (P52), from its quantity and app.stock.low-threshold (StockService.status).
// Labels: stores.status.<NAME> in messages.properties. key() is the value of the Stores page's ?status= filter.
public enum StockStatus {

    // At or above the threshold.
    OK,
    // Above zero and below the threshold.
    LOW,
    // Zero, or no StockLevel row at all.
    OUT;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String labelKey() {
        return "stores.status." + name();
    }

    /** The status with this key ("ok", "low", "out"), or null for null, blank or an unknown key. */
    public static StockStatus fromKey(String key) {
        return Arrays.stream(values()).filter(status -> status.key().equals(key)).findFirst().orElse(null);
    }
}
