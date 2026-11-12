package com.nereonix.nereonet_mvp.ship;

/**
 * The number on each tab of the ship page: the rows that tab lists, except Overview, which counts the ship's open
 * items (the same rule as the fleet Overview: expired certificates, certificates expiring within 30 days, orders sent
 * and not exported).
 */
public record ShipTabCounts(long openItems, long crew, long certificates, long library, long items,
                            long announcements) {

    public long of(ShipTab tab) {
        return switch (tab) {
            case OVERVIEW -> openItems;
            case CREW -> crew;
            case CERTIFICATES -> certificates;
            case LIBRARY -> library;
            case ITEMS -> items;
            case ANNOUNCEMENTS -> announcements;
        };
    }
}
