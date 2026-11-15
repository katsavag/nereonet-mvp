package com.nereonix.nereonet_mvp.core.list;

import java.time.LocalDate;
import java.util.List;
import java.util.function.BiFunction;

/**
 * One quick choice of a date range (P58, layout/parts :: dateRange): "Last 30 days" is From = today minus 30 days and
 * To = today, both UTC days. A plain link to the page with those dates (and the page's other filters), so it works
 * without JavaScript. current: the dates shown are exactly this choice's, so the link is marked (aria-current); dates
 * typed by hand match none.
 *
 * @param days    how many days back From is
 * @param url     the page with this range, from page 1
 * @param current whether the page shows this range now
 */
public record DatePreset(int days, String url, boolean current) {

    /** The choices of the history and log pages: the last 30, 60 and 90 days. */
    public static final List<Integer> LAST_DAYS = List.of(30, 60, 90);

    /**
     * The choices for these numbers of days. today: the UTC date; from and to: the dates the page shows (either may
     * be null); url: the page's link for a range, From then To.
     */
    public static List<DatePreset> of(List<Integer> days, LocalDate today, LocalDate from, LocalDate to,
                                      BiFunction<LocalDate, LocalDate, String> url) {
        return days.stream().map(n -> {
            LocalDate start = today.minusDays(n);
            return new DatePreset(n, url.apply(start, today), start.equals(from) && today.equals(to));
        }).toList();
    }
}
