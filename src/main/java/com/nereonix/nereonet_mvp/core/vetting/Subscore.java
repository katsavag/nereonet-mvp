package com.nereonix.nereonet_mvp.core.vetting;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The six RightShip subscores (P40), in RightShip's order, each in its impact group: the lowest subscore limits the
 * overall Safety Score. Labels: vetting.subscore.name (lower case); key() is the form field's name.
 */
public enum Subscore {
    VESSEL_INCIDENT(Impact.HIGHEST, "vesselIncident"),
    DOC(Impact.HIGHEST, "doc"),
    PSC_DEFICIENCIES(Impact.MEDIUM, "pscDeficiencies"),
    DETENTIONS(Impact.MEDIUM, "detentions"),
    FLAG(Impact.LOWEST, "flag"),
    CLASS(Impact.LOWEST, "classPerformance");

    /** The impact groups, highest first. Labels: vetting.impact.name (lower case). */
    public enum Impact {
        HIGHEST, MEDIUM, LOWEST;

        public List<Subscore> subscores() {
            return Arrays.stream(Subscore.values()).filter(s -> s.impact == this).toList();
        }

        public String messageKey() {
            return "vetting.impact." + name().toLowerCase(Locale.ROOT);
        }
    }

    private final Impact impact;
    private final String key;

    Subscore(Impact impact, String key) {
        this.impact = impact;
        this.key = key;
    }

    public Impact impact() {
        return impact;
    }

    /** The form field's name, e.g. "pscDeficiencies". */
    public String key() {
        return key;
    }

    /** The API gives this one (docSafetyScore); the other five come from the last manual entry. */
    public boolean fromApi() {
        return this == DOC;
    }

    public String messageKey() {
        return "vetting.subscore." + name().toLowerCase(Locale.ROOT);
    }
}
