package com.nereonix.nereonet_mvp.requirement;

import java.util.List;

import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipType;

/**
 * What the Required documents page's combined filter chose (P43): a vessel type ("all", "TANKER", ...) or an active
 * vessel ("ship-12"), whose type's grid is shown. Requirements stay per vessel type: picking a vessel is only a way to
 * find the right grid.
 *
 * @param key      the value in the URL and the select, as chosen
 * @param type     the vessel type whose grid is shown; null for All vessels
 * @param ship     the chosen vessel, or null when a type was chosen
 * @param vessels  the active vessels of that type, by name (every active vessel without a type for All vessels):
 *                 the note "Rules for Bulk carrier, which apply to ALDER, LAUREL and ROWAN."
 * @param options  what the filter offers: the vessel types, then the active vessels
 */
public record RequirementSelection(String key, ShipType type, Ship ship, List<Ship> vessels, List<Option> options) {

    /** One choice of the filter: its value, its name and its kind ("vessel type", or the vessel's type). */
    public record Option(String value, String label, String kind, boolean vessel) {
    }

    /** The URL value of a vessel: "ship-12". */
    public static String shipKey(Ship ship) {
        return "ship-" + ship.getId();
    }

    /** The vessel type's URL value: what the grid form posts and Save returns to. */
    public String typeKey() {
        return RequirementService.key(type);
    }

    /** The vessels' names, "ALDER, LAUREL and ROWAN", with "and" as given. */
    public String vesselNames(String and) {
        List<String> names = vessels.stream().map(Ship::getName).toList();
        if (names.size() <= 1) {
            return String.join("", names);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + and + names.getLast();
    }
}
