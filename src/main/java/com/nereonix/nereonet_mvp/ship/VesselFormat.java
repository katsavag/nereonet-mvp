package com.nereonix.nereonet_mvp.ship;

import java.text.NumberFormat;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Numbers of the Vessel data tab (P45), as {@code @vesselFormat} in templates: grouped and with the decimal mark of
 * the page language, at most two decimals, no trailing zeros ("40,562", "11.4", "225"). Empty for null.
 */
@Component("vesselFormat")
public class VesselFormat {

    public String number(Number value) {
        if (value == null) {
            return "";
        }
        NumberFormat format = NumberFormat.getNumberInstance(LocaleContextHolder.getLocale());
        format.setMaximumFractionDigits(2);
        return format.format(value);
    }

    /** VesselAPI's country and country code point to different countries (the Vessel data tab says so). */
    public boolean flagConflict(com.nereonix.nereonet_mvp.core.vesseldata.VesselParticulars particulars) {
        VesselProposal proposal = VesselProposal.of(particulars);
        return proposal != null && proposal.flagConflict();
    }

    /** The ISO code of VesselAPI's country name ("Malta" -> "MT"), for the flag next to it; null if unknown. */
    public String codeOfName(String name) {
        return Countries.codeOfName(name);
    }

    /** The ISO code of VesselAPI's country code ("MT" or "MLT" -> "MT"), for the flag next to it; null if unknown. */
    public String codeOf(String code) {
        return Countries.codeOf(code);
    }

    /** The ISO code of a flag field's text ("Malta (MT)" -> "MT"), for the flag next to it; null if unknown. */
    public String codeOfField(String input) {
        return Countries.toCode(input);
    }

    /** A number with its unit, "225 m", "9,480 kW"; null for no number (so the template shows a dash). */
    public String withUnit(Number value, String unit) {
        if (value == null) {
            return null;
        }
        return unit == null || unit.isBlank() ? number(value) : number(value) + " " + unit.strip();
    }
}
