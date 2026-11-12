package com.nereonix.nereonet_mvp.core.list;

/**
 * A column a list can be sorted by (P19): its key in the URL (?sort=key) and the direction a first click on its
 * header gives (descending for dates, so the newest come first).
 */
public record ListSort(String key, boolean descendingFirst) {

    public static ListSort asc(String key) {
        return new ListSort(key, false);
    }

    public static ListSort desc(String key) {
        return new ListSort(key, true);
    }
}
