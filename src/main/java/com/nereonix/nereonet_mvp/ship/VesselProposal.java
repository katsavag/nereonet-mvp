package com.nereonix.nereonet_mvp.ship;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.ship.ShipType;
import com.nereonix.nereonet_mvp.core.vesseldata.VesselParticulars;

/**
 * What a VesselAPI lookup proposes for the vessel's own fields (P45), next to the provider's values it came from.
 *   name           as VesselAPI gives it
 *   type           vessel_subtype, else vessel_type, mapped to ShipType; OTHER when neither says clearly; null when
 *                  both are missing
 *   flag           the ISO code of country_code (else of the country name); null when they disagree (flagConflict):
 *                  then the user chooses between flagByCountry and flagByCode, and the saved flag never changes
 *                  silently
 */
public record VesselProposal(String name, ShipType type, String providerType, String providerCountry,
                             String providerCountryCode, String flag, String flagByCountry, String flagByCode,
                             boolean flagConflict) {

    /** Null when the particulars call did not work. */
    public static VesselProposal of(VesselParticulars particulars) {
        if (particulars == null) {
            return null;
        }
        String byCountry = Countries.codeOfName(particulars.country());
        String byCode = Countries.codeOf(particulars.countryCode());
        boolean conflict = byCountry != null && byCode != null && !byCountry.equals(byCode);
        return new VesselProposal(particulars.name(), type(particulars.vesselSubtype(), particulars.vesselType()),
                particulars.typeText(), particulars.country(), particulars.countryCode(),
                conflict ? null : byCode != null ? byCode : byCountry, byCountry, byCode, conflict);
    }

    /** The ShipType a provider type means; OTHER when unclear, null when there is no text at all. */
    static ShipType type(String... texts) {
        boolean any = false;
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            any = true;
            String t = text.toLowerCase(Locale.ROOT);
            if (t.contains("bulk") || t.contains("ore carrier")) {
                return ShipType.BULK_CARRIER;
            }
            if (t.contains("tanker")) {
                return ShipType.TANKER;
            }
            if (t.contains("container")) {
                return ShipType.CONTAINER;
            }
            if (t.contains("general cargo")) {
                return ShipType.GENERAL_CARGO;
            }
        }
        return any ? ShipType.OTHER : null;
    }

    /** The two flags to choose from when they disagree (by country, by code); empty otherwise. */
    public List<String> flagChoices() {
        List<String> choices = new ArrayList<>();
        if (flagConflict) {
            choices.add(flagByCountry);
            choices.add(flagByCode);
        }
        return choices;
    }

    /** This name differs from the saved one (ignoring case and spaces at the ends). */
    public boolean nameDiffers(String saved) {
        return name != null && (saved == null || !name.strip().equalsIgnoreCase(saved.strip()));
    }

    public boolean typeDiffers(ShipType saved) {
        return type != null && type != saved;
    }

    /** A flag without a conflict that differs from the saved one. */
    public boolean flagDiffers(String saved) {
        return !flagConflict && flag != null && !flag.equals(saved);
    }

    /** Edit vessel: anything to offer in the Saved / VesselAPI table. */
    public boolean differsFrom(Ship ship) {
        return nameDiffers(ship.getName()) || typeDiffers(ship.getType()) || flagDiffers(ship.getFlag())
                || flagConflict;
    }
}
