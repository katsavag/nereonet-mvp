package com.nereonix.nereonet_mvp.core.ship;

// A number of rows for one ship, from a "group by ship" count query.
public record ShipCount(Long shipId, long count) {
}
