package com.nereonix.nereonet_mvp.ship;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Experiment: the "Position" card on the ship page's Overview tab (ship/map :: card), the free VesselFinder map
 * tracking the ship by its IMO number. Used as {@code @shipMap} in Thymeleaf. Switched by app.ship-map.enabled
 * (default true); off, the page shows no map and loads no external script. The browser loads the map from
 * vesselfinder.com; the server never calls VesselFinder and stores no positions.
 * To remove the experiment: this class, templates/ship/map.html, its th:replace in ship/overview.html, the .ship-map
 * CSS, the ship.map.* messages and app.ship-map in application.yml.
 */
@Component("shipMap")
public class ShipMap {

    private final boolean enabled;

    public ShipMap(@Value("${app.ship-map.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }
}
