package com.nereonix.nereonet_mvp.ship;

/**
 * The number on each tab of the ship page: the rows that tab lists, except Overview, which counts the ship's open
 * items (the same rule as the fleet Overview: expired certificates, certificates expiring within 30 days, orders sent
 * and not exported). The Vessel data tab (P45) has no number: NO_COUNT.
 */
public record ShipTabCounts(long openItems, long crew, long certificates, long library, long items,
                            long announcements) {

    /** A tab without a number (Vessel data, P45): the tab bar shows none. */
    public static final long NO_COUNT = -1;

    public long of(ShipTab tab) {
        return switch (tab) {
            case OVERVIEW -> openItems;
            case VESSEL -> NO_COUNT;
            case CREW -> crew;
            case CERTIFICATES -> certificates;
            case LIBRARY -> library;
            case ITEMS -> items;
            case ANNOUNCEMENTS -> announcements;
        };
    }
}
