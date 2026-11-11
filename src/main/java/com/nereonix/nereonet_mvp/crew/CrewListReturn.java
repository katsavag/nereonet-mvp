package com.nereonix.nereonet_mvp.crew;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.web.util.UriComponentsBuilder;

import com.nereonix.nereonet_mvp.ship.ShipTab;

/**
 * Where an invitation's Resend or Cancel returns to (P17): the list the button was on. The forms send it back as
 * fields (params()); the controller rebuilds it from them with of(...). Never a URL taken from the request: only
 * known keys, a numeric ship id and a known status.
 * <ul>
 *   <li>tab=crew&amp;shipId=3: the ship page's Crew tab, /office/ships/3/crew;</li>
 *   <li>returnTo=crew, with the filters shipId and status when set: the fleet Crew list, /office/crew?shipId=3&amp;status=expired;</li>
 *   <li>returnTo=overview: the Overview (P09);</li>
 *   <li>anything else: Office users, where office invitations are listed.</li>
 * </ul>
 */
public record CrewListReturn(Kind kind, Long shipId, CrewStatus status) {

    public enum Kind { OFFICE_USERS, OVERVIEW, CREW, SHIP_TAB }

    public static CrewListReturn of(String returnTo, String tab, Long shipId, String status) {
        if (ShipTab.of(tab).orElse(null) == ShipTab.CREW && shipId != null) {
            return shipTab(shipId);
        }
        if ("crew".equals(returnTo)) {
            return fleet(shipId, CrewStatus.of(status).orElse(null));
        }
        return new CrewListReturn("overview".equals(returnTo) ? Kind.OVERVIEW : Kind.OFFICE_USERS, null, null);
    }

    public static CrewListReturn shipTab(Long shipId) {
        return new CrewListReturn(Kind.SHIP_TAB, shipId, null);
    }

    public static CrewListReturn fleet(Long shipId, CrewStatus status) {
        return new CrewListReturn(Kind.CREW, shipId, status);
    }

    /** The page to go back to. */
    public String url() {
        return switch (kind) {
            case OFFICE_USERS -> "/office/users";
            case OVERVIEW -> "/office";
            case SHIP_TAB -> ShipTab.CREW.url(shipId);
            case CREW -> UriComponentsBuilder.fromPath("/office/crew")
                    .queryParamIfPresent("shipId", Optional.ofNullable(shipId))
                    .queryParamIfPresent("status", Optional.ofNullable(status).map(CrewStatus::key))
                    .build().toUriString();
        };
    }

    /** The fields a Resend or Cancel form sends, in this order; empty for Office users. */
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
                if (status != null) {
                    params.put("status", status.key());
                }
            }
            case OVERVIEW -> params.put("returnTo", "overview");
            case OFFICE_USERS -> { }
        }
        return params;
    }
}
