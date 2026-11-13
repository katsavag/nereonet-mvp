package com.nereonix.nereonet_mvp.core.list;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.Page;

import com.nereonix.nereonet_mvp.core.ship.Ship;

/**
 * The state of one office list (P19): search, filters, sort, page and page size, all in the URL so a list can be
 * reloaded, bookmarked and linked to (the Overview links to /office/crew?status=expired). The controller builds it
 * from the request with of(...) and adds its filters with filter(...); the service reads it to query one page in
 * the database (ListQueries, or its own SQL); the shared fragments (layout/parts :: filterBar, sortHeader, pager,
 * listEmpty) build every link from it.
 * <ul>
 *   <li>q: the search text, trimmed, at most 100 characters; null when empty;</li>
 *   <li>sort and dir: one of the list's ListSorts (the first is the default), asc or desc;</li>
 *   <li>page (1-based in the URL) and size: 10 (the default, P43; 25 before, and 20 on the audit log), 20, 50 or
 *   100, the same on every list.</li>
 * </ul>
 * Changing the search, a filter, the sort or the page size goes back to page 1.
 * The parameters are written in a fixed order: q, the filters in the order added, sort, dir, size, page.
 */
public final class ListQuery {

    public static final List<Integer> SIZES = List.of(10, 20, 50, 100);
    public static final int DEFAULT_SIZE = SIZES.getFirst();
    static final int MAX_SEARCH_LENGTH = 100;

    /** A removable chip of an active filter: "Ship: MV Demo". value is text as it is, valueKey a message key; either
     *  may be null (a filter without a value, such as "Not exported yet"). */
    public record Chip(String labelKey, String value, String valueKey, String removeUrl) {
    }

    private record Filter(String name, String value, String labelKey, String valueText, String valueKey) {
    }

    private final String path;
    private final String q;
    private final List<ListSort> sorts;
    private final ListSort sort;
    private final boolean descending;
    private final boolean sortChosen;
    private final int page;
    private final int size;
    private final List<Integer> sizes;
    private final List<Filter> filters = new ArrayList<>();

    private ListQuery(String path, String q, List<ListSort> sorts, ListSort sort, boolean descending,
                      boolean sortChosen, int page, int size, List<Integer> sizes) {
        this.path = path;
        this.q = q;
        this.sorts = sorts;
        this.sort = sort;
        this.descending = descending;
        this.sortChosen = sortChosen;
        this.page = page;
        this.size = size;
        this.sizes = sizes;
    }

    /**
     * path: the list's URL within the application, e.g. /office/crew or /office/ships/3/items. sorts: the columns it
     * can be sorted by, the default first. An unknown sort key, direction or size, or a page below 1, falls back to
     * the default, so any URL shows a list.
     */
    public static ListQuery of(String path, ListRequest request, ListSort... sorts) {
        ListRequest r = request == null ? ListRequest.EMPTY : request;
        List<ListSort> all = Arrays.asList(sorts);
        ListSort chosen = all.stream().filter(s -> s.key().equals(r.sort())).findFirst().orElse(null);
        ListSort sort = chosen == null ? all.getFirst() : chosen;
        boolean descending = "desc".equals(r.dir()) || (!"asc".equals(r.dir()) && sort.descendingFirst());
        int size = r.size() != null && SIZES.contains(r.size()) ? r.size() : DEFAULT_SIZE;
        int page = r.page() == null || r.page() < 1 ? 0 : r.page() - 1;
        return new ListQuery(path, search(r.q()), all, sort, descending, chosen != null, page, size, SIZES);
    }

    private static String search(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String trimmed = q.strip();
        return trimmed.length() > MAX_SEARCH_LENGTH ? trimmed.substring(0, MAX_SEARCH_LENGTH) : trimmed;
    }

    /** An active filter, kept in every link, with its chip "label: valueText". Nothing when value is null. */
    public ListQuery filter(String name, Object value, String labelKey, String valueText) {
        if (value != null) {
            filters.add(new Filter(name, value.toString(), labelKey, valueText, null));
        }
        return this;
    }

    /** The ship filter (?shipId=), shared by the fleet lists: its chip is "Ship: MV Demo". ships: the ships the
     *  filter offers, to find the name. */
    public ListQuery filterShip(Long shipId, List<Ship> ships) {
        if (shipId == null) {
            return this;
        }
        String name = ships.stream().filter(s -> s.getId().equals(shipId)).map(Ship::getName).findFirst()
                .orElse(shipId.toString());
        return filter("shipId", shipId, "list.filter.ship", name);
    }

    /** An active filter whose chip value is a message key: "Status: Invitation expired". */
    public ListQuery filterKey(String name, Object value, String labelKey, String valueKey) {
        if (value != null) {
            filters.add(new Filter(name, value.toString(), labelKey, null, valueKey));
        }
        return this;
    }

    // Read by the services.

    public String getPath() {
        return path;
    }

    /** The search text, or null. */
    public String getQ() {
        return q;
    }

    /** The search as a LIKE pattern on a lower-cased column, % and _ escaped with \: "%rice%"; null without search. */
    public String getContains() {
        if (q == null) {
            return null;
        }
        String escaped = q.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    public String getSort() {
        return sort.key();
    }

    public boolean isDescending() {
        return descending;
    }

    /** Zero-based. */
    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    // Read by the fragments.

    /** A search or a filter is set: an empty list then says "No results for these filters". */
    public boolean isFiltered() {
        return q != null || !filters.isEmpty();
    }

    /**
     * No rows and no search or filter set (P25): the list's own empty state, which says what the list is for and
     * offers its first action, takes the place of the page header's actions. Not "No results for these filters".
     */
    public boolean emptyUnfiltered(Page<?> rows) {
        return rows.getTotalElements() == 0 && !isFiltered();
    }

    /** The chips of the active search and filters, each with the link that removes it. */
    public List<Chip> getChips() {
        List<Chip> chips = new ArrayList<>();
        if (q != null) {
            chips.add(new Chip("list.search.chip", q, null, url(null, null, sortChosen, size, 1)));
        }
        for (Filter f : filters) {
            chips.add(new Chip(f.labelKey(), f.valueText(), f.valueKey(), url(q, f.name(), sortChosen, size, 1)));
        }
        return chips;
    }

    /** Every search and filter removed; the sort and page size stay. */
    public String getClearUrl() {
        return build(null, null, sortChosen, sort.key(), descending, size, 1, false);
    }

    /** The fields the filter form sends besides the search and the filters, so a new filter keeps the sort and size. */
    public Map<String, String> getHidden() {
        Map<String, String> hidden = new LinkedHashMap<>();
        if (sortChosen) {
            hidden.put("sort", sort.key());
            hidden.put("dir", descending ? "desc" : "asc");
        }
        if (size != defaultSize()) {
            hidden.put("size", Integer.toString(size));
        }
        return hidden;
    }

    /** aria-sort of the column header: "ascending" or "descending" for the sorted column, null for the others. */
    public String ariaSort(String key) {
        if (!sort.key().equals(key)) {
            return null;
        }
        return descending ? "descending" : "ascending";
    }

    /** The column header's link: the other direction for the sorted column, else its first direction; page 1. */
    public String sortUrl(String key) {
        ListSort target = sorts.stream().filter(s -> s.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Not a sort of " + path + ": " + key));
        boolean desc = target.key().equals(sort.key()) ? !descending : target.descendingFirst();
        return build(q, null, true, target.key(), desc, size, 1, true);
    }

    /** The link to a page, zero-based as in Spring's Page. */
    public String pageUrl(int zeroBasedPage) {
        return url(q, null, sortChosen, size, zeroBasedPage + 1);
    }

    /** The link to page 1 with this page size. */
    public String sizeUrl(int newSize) {
        return url(q, null, sortChosen, newSize, 1);
    }

    /** The page sizes offered, the default first. */
    public List<Integer> getSizes() {
        return sizes;
    }

    private int defaultSize() {
        return sizes.getFirst();
    }

    /** The pager is shown when there is more than the smallest page. */
    public boolean pagerShown(Page<?> rows) {
        return rows.getTotalElements() > sizes.stream().min(Integer::compare).orElseThrow();
    }

    /** "1 to 25 of 412": the first row of the page, 1-based. */
    public long first(Page<?> rows) {
        return rows.getNumberOfElements() == 0 ? 0 : (long) rows.getNumber() * rows.getSize() + 1;
    }

    public long last(Page<?> rows) {
        return (long) rows.getNumber() * rows.getSize() + rows.getNumberOfElements();
    }

    /** The same search, filters and chosen sort at another path, without page and page size: the list's export
     *  (P20), e.g. /office/certificates/export?status=expired. */
    public String urlAt(String otherPath) {
        String url = build(q, null, sortChosen, sort.key(), descending, defaultSize(), 1, true);
        return otherPath + url.substring(path.length());
    }

    /** This list's link as it is. */
    public String getUrl() {
        return url(q, null, sortChosen, size, page + 1);
    }

    /** The shared part of this list's state as the URL had it, to send back with an action that returns here
     *  (crew/CrewListReturn). */
    public ListRequest getRequest() {
        return new ListRequest(q, sortChosen ? sort.key() : null, sortChosen ? (descending ? "desc" : "asc") : null,
                page > 0 ? page + 1 : null, size != defaultSize() ? size : null);
    }

    // The current sort; without: the filter to leave out, or null.
    private String url(String search, String without, boolean withSort, int pageSize, int page1) {
        return build(search, without, withSort, sort.key(), descending, pageSize, page1, true);
    }

    // without: the filter to leave out; withFilters false leaves out all of them.
    private String build(String search, String without, boolean withSort, String sortKey, boolean desc, int pageSize,
                         int page1, boolean withFilters) {
        List<String> params = new ArrayList<>();
        if (search != null) {
            params.add(param("q", search));
        }
        if (withFilters) {
            for (Filter f : filters) {
                if (!f.name().equals(without)) {
                    params.add(param(f.name(), f.value()));
                }
            }
        }
        if (withSort) {
            params.add(param("sort", sortKey));
            params.add(param("dir", desc ? "desc" : "asc"));
        }
        if (pageSize != defaultSize()) {
            params.add(param("size", Integer.toString(pageSize)));
        }
        if (page1 > 1) {
            params.add(param("page", Integer.toString(page1)));
        }
        return params.isEmpty() ? path : path + "?" + String.join("&", params);
    }

    // Form encoding (space as +, + as %2B), as the browser sends the filter form.
    private static String param(String name, String value) {
        return name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
