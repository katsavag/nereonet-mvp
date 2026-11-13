package com.nereonix.nereonet_mvp.requirement;

import java.util.List;
import java.util.Set;

import com.nereonix.nereonet_mvp.certificate.CertificateTypes;
import com.nereonix.nereonet_mvp.core.ship.ShipType;
import com.nereonix.nereonet_mvp.core.user.Rank;

/**
 * The Required documents page (P42) for one vessel type: certificate types as rows, ranks grouped by department as
 * columns. vesselType: null for All vessels. suggestions: some row of any vessel type is still a starter template
 * suggestion (the notice at the top).
 */
public record RequirementGrid(ShipType vesselType, List<Rank.Group> groups, List<Row> rows, boolean suggestions) {

    /**
     * One certificate type. ranks: ticked for this vessel type; inherited: ticked on All vessels (shown, not editable,
     * on a vessel type's grid); suggestion: a starter template row not reviewed yet; added: the row the office just
     * asked for (?add=), not saved yet.
     */
    public record Row(String type, Set<Rank> ranks, Set<Rank> inherited, boolean optional, String note,
                      boolean suggestion, boolean added) {

        /** The type's key (P20 matching), for the note errors of a posted grid. */
        public String key() {
            return CertificateTypes.sameTypeKey(type);
        }

        public boolean ticked(Rank rank) {
            return ranks.contains(rank);
        }

        public boolean inherits(Rank rank) {
            return inherited.contains(rank);
        }
    }

    /** The URL value of the vessel type: "all" or the ShipType's name. */
    public String vesselTypeKey() {
        return RequirementService.key(vesselType);
    }

    public int rankCount() {
        return groups.stream().mapToInt(g -> g.ranks().size()).sum();
    }
}
