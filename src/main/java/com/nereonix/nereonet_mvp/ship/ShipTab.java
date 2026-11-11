package com.nereonix.nereonet_mvp.ship;

import java.util.Arrays;
import java.util.Optional;

/**
 * The tabs of the ship page, in the order shown. Each tab is its own URL, /office/ships/{id} plus segment(), so it
 * can be bookmarked. key() is what templates compare (shipTab == 'crew') and what forms send back in their "tab"
 * field, so an action started from a tab returns to it. Labels: ship.tab.<key> in messages.properties.
 */
public enum ShipTab {

    OVERVIEW("overview", ""),
    CREW("crew", "/crew"),
    CERTIFICATES("certificates", "/certificates"),
    LIBRARY("library", "/library"),
    ITEMS("items", "/items"),
    ANNOUNCEMENTS("announcements", "/announcements"),
    REQUESTS("requests", "/requests");

    private final String key;
    private final String segment;

    ShipTab(String key, String segment) {
        this.key = key;
        this.segment = segment;
    }

    public String key() {
        return key;
    }

    /** The tab's URL within the application, e.g. /office/ships/3/crew. */
    public String url(Long shipId) {
        return "/office/ships/" + shipId + segment;
    }

    /** The tab with this key; empty for null or anything else, so a posted value can never become another URL. */
    public static Optional<ShipTab> of(String key) {
        return Arrays.stream(values()).filter(tab -> tab.key.equals(key)).findFirst();
    }
}
