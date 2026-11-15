package com.nereonix.nereonet_mvp.requirement;

import java.util.List;
import java.util.Set;

import com.nereonix.nereonet_mvp.certificate.CertificateTypes;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.Rank;

/**
 * The Required documents page (P42) for one vessel (P58; one vessel type before): certificate types as rows, ranks
 * grouped by department as columns. suggestions: some row of this vessel is still a default it was given and the
 * office has not saved (the notice at the top). resetChanges: what "Reset to Bulk carrier defaults" would change, one
 * line each in the request's language (empty: the vessel has its type's defaults).
 */
public record RequirementGrid(Ship ship, List<Rank.Group> groups, List<Row> rows, boolean suggestions,
                             List<String> resetChanges) {

    /**
     * One certificate type. ranks: ticked on this vessel; suggestion: a default not reviewed yet; added: the row the
     * office just asked for (?add=), not saved yet; changed: differs from the defaults of the vessel's type (ranks,
     * Optional or note; a row of the defaults the vessel no longer requires, or one it requires beyond them).
     */
    public record Row(String type, Set<Rank> ranks, boolean optional, String note, boolean suggestion, boolean added,
                      boolean changed) {

        /** The type's key (P20 matching), for the note errors of a posted grid. */
        public String key() {
            return CertificateTypes.sameTypeKey(type);
        }

        public boolean ticked(Rank rank) {
            return ranks.contains(rank);
        }
    }

    public int rankCount() {
        return groups.stream().mapToInt(g -> g.ranks().size()).sum();
    }

    /** Whether the vessel differs from its type's defaults at all. */
    public boolean changedFromDefaults() {
        return !resetChanges.isEmpty();
    }
}
