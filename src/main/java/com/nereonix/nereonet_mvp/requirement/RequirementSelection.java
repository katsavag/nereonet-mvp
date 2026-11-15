package com.nereonix.nereonet_mvp.requirement;

import java.util.List;

import com.nereonix.nereonet_mvp.core.ship.Ship;

/**
 * What the Required documents page's vessel filter chose (P58: vessels only, each with its own requirements):
 * one of the company's active vessels, by id (?vessel=12); the first by name when none (or an unknown or inactive one)
 * is chosen.
 *
 * @param ship    the vessel whose grid is shown; null when the company has no active vessel
 * @param options what the filter offers: the active vessels by name, each with its type ("ALDER · Bulk carrier")
 */
public record RequirementSelection(Ship ship, List<Option> options) {

    /** One vessel of the filter: its id as the value, its name and its kind (its type, or "type not set"). */
    public record Option(String value, String label, String kind) {
    }

    /** The URL and form value of the vessel shown: its id, or "" without one. */
    public String key() {
        return ship == null ? "" : String.valueOf(ship.getId());
    }
}
