package com.nereonix.nereonet_mvp.crew;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.list.ListRequest;
import com.nereonix.nereonet_mvp.ship.ShipTab;

/**
 * Where an invitation's Resend or Cancel returns to (P17): the list the button was on, as it was (P19: its search,
 * filters, sort, page and page size). The forms send it back as fields (params()); the controller rebuilds it from
 * them with of(...). Never a URL taken from the request: only known keys, a numeric ship id, a known status, a sort
 * key of letters, asc or desc, a known page size and a page number; the search is encoded.
 * <ul>
 *   <li>tab=crew&amp;shipId=3, with the status filter when set: the ship page's Crew tab, /office/ships/3/crew;</li>
 *   <li>returnTo=crew, with the filters shipId and status when set: the fleet Crew list, /office/crew?shipId=3&amp;status=expired;</li>
 *   <li>returnTo=overview: the Overview (P09);</li>
 *   <li>anything else: Office users, where office invitations are listed, with its status filter.</li>
 * </ul>
 */
public record CrewListReturn(Kind kind, Long shipId, CrewStatus status, ListRequest list) {

    public enum Kind { OFFICE_USERS, OVERVIEW, CREW, SHIP_TAB }

    private static final Pattern SORT_KEY = Pattern.compile("[a-zA-Z]{1,30}");

    public static CrewListReturn of(String returnTo, String tab, Long shipId, String status, ListRequest list) {
        CrewStatus crewStatus = CrewStatus.of(status).orElse(null);
        ListRequest clean = clean(list);
        if (ShipTab.of(tab).orElse(null) == ShipTab.CREW && shipId != null) {
            return new CrewListReturn(Kind.SHIP_TAB, shipId, crewStatus, clean);
        }
        if ("crew".equals(returnTo)) {
            return new CrewListReturn(Kind.CREW, shipId, crewStatus, clean);
        }
        if ("overview".equals(returnTo)) {
            return new CrewListReturn(Kind.OVERVIEW, null, null, ListRequest.EMPTY);
        }
        return new CrewListReturn(Kind.OFFICE_USERS, null, crewStatus, clean);
    }

    public static CrewListReturn shipTab(Long shipId, CrewStatus status, ListRequest list) {
        return new CrewListReturn(Kind.SHIP_TAB, shipId, status, clean(list));
    }

    public static CrewListReturn fleet(Long shipId, CrewStatus status, ListRequest list) {
        return new CrewListReturn(Kind.CREW, shipId, status, clean(list));
    }

    public static CrewListReturn officeUsers(CrewStatus status, ListRequest list) {
        return new CrewListReturn(Kind.OFFICE_USERS, null, status, clean(list));
    }

    /** The page to go back to, with its query string in the order ListQuery writes it. */
    public String url() {
        String path = switch (kind) {
            case OFFICE_USERS -> "/office/users";
            case OVERVIEW -> "/office";
            case SHIP_TAB -> ShipTab.CREW.url(shipId);
            case CREW -> "/office/crew";
        };
        Map<String, String> query = new LinkedHashMap<>();
        if (kind != Kind.OVERVIEW) {
            put(query, "q", list.q());
            if (kind == Kind.CREW && shipId != null) {
                query.put("shipId", shipId.toString());
            }
            if (status != null) {
                query.put("status", status.key());
            }
            putListTail(query);
        }
        if (query.isEmpty()) {
            return path;
        }
        return path + "?" + String.join("&", query.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).toList());
    }

    /** The fields a Resend or Cancel form sends, in this order. */
    public Map<String, String> params() {
        Map<String, String> params = new LinkedHashMap<>();
        switch (kind) {
            case SHIP_TAB -> {
                params.put("tab", ShipTab.CREW.key());
                params.put("shipId", shipId.toString());
            }
            case CREW -> {
                params.put("returnTo", "crew");
                if (shipId != null) {
                    params.put("shipId", shipId.toString());
                }
            }
            case OVERVIEW -> params.put("returnTo", "overview");
            case OFFICE_USERS -> { }
        }
        if (kind != Kind.OVERVIEW) {
            if (status != null) {
                params.put("status", status.key());
            }
            put(params, "q", list.q());
            putListTail(params);
        }
        return params;
    }

    private void putListTail(Map<String, String> map) {
        put(map, "sort", list.sort());
        put(map, "dir", list.dir());
        put(map, "size", list.size() == null ? null : list.size().toString());
        put(map, "page", list.page() == null ? null : list.page().toString());
    }

    private static void put(Map<String, String> map, String name, String value) {
        if (value != null) {
            map.put(name, value);
        }
    }

    // Only values a list can show; anything else is dropped (the list's default).
    private static ListRequest clean(ListRequest list) {
        if (list == null) {
            return ListRequest.EMPTY;
        }
        String q = list.q() == null || list.q().isBlank() ? null : list.q().strip();
        String sort = list.sort() != null && SORT_KEY.matcher(list.sort()).matches() ? list.sort() : null;
        String dir = "asc".equals(list.dir()) || "desc".equals(list.dir()) ? list.dir() : null;
        Integer size = list.size() != null && list.size() != ListQuery.DEFAULT_SIZE
                && ListQuery.SIZES.contains(list.size()) ? list.size() : null;
        Integer page = list.page() != null && list.page() > 1 ? list.page() : null;
        return new ListRequest(q, sort, sort == null ? null : dir, page, size);
    }
}
