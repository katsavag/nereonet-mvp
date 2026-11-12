package com.nereonix.nereonet_mvp.core.library;

import java.util.Map;

// For a ship's library page: how many of the crew on board now acknowledged the current version of each item.
public record AcknowledgementCounts(long crew, Map<Long, Long> byItem) {

    public long of(Long itemId) {
        return byItem.getOrDefault(itemId, 0L);
    }

    /** The share that acknowledged, 0 to 100 (whole percent, rounded down), for the progress bar (P22). */
    public long percentOf(Long itemId) {
        return crew == 0 ? 0 : Math.min(100, of(itemId) * 100 / crew);
    }

    /** Everyone on board acknowledged (and there is someone on board). */
    public boolean completeFor(Long itemId) {
        return crew > 0 && of(itemId) >= crew;
    }
}
