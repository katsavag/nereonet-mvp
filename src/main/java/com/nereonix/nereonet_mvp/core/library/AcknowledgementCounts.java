package com.nereonix.nereonet_mvp.core.library;

import java.util.Map;

// For a ship's library page: how many of the crew on board now acknowledged the current version of each item.
public record AcknowledgementCounts(long crew, Map<Long, Long> byItem) {

    public long of(Long itemId) {
        return byItem.getOrDefault(itemId, 0L);
    }
}
