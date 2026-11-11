package com.nereonix.nereonet_mvp.core.library;

// A number of rows for one library item, from a "group by item" count query.
public record ItemCount(Long itemId, long count) {
}
