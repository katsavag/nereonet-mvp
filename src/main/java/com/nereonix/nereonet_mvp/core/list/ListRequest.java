package com.nereonix.nereonet_mvp.core.list;

/**
 * The part of a list's URL that every list shares (P19): ?q=search&amp;sort=name&amp;dir=desc&amp;page=2&amp;size=50.
 * Bound from the query string with @ModelAttribute; every value may be missing or wrong, ListQuery.of() falls back
 * to the list's defaults. page is 1-based, as the user sees it.
 */
public record ListRequest(String q, String sort, String dir, Integer page, Integer size) {

    /** No search, the default sort, the first page. */
    public static final ListRequest EMPTY = new ListRequest(null, null, null, null, null);
}
