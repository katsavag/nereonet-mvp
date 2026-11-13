package com.nereonix.nereonet_mvp.requirement;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import com.nereonix.nereonet_mvp.core.export.XlsExport;
import com.nereonix.nereonet_mvp.core.ship.Ship;
import com.nereonix.nereonet_mvp.core.user.User;

/**
 * The required documents of a list of people as an Excel workbook (P42, the P39/P40 export conventions: .xls, a
 * frozen bold header, real date cells), sheet "Required documents", one row per person and document, in the order
 * the documents are shown on the person's page (required first, missing first): imo_number, ship_name, crew_member,
 * rank (Rank's name), requirement (the certificate type), required (yes, or no for an optional one), state (missing,
 * expired, expiring or valid) and expires_on (the date of the certificate held, a date cell; empty when missing).
 * A person without required documents has no row. The column names are the file format, never translated.
 */
public final class ComplianceExport {

    static final List<String> HEADER = List.of("imo_number", "ship_name", "crew_member", "rank", "requirement",
            "required", "state", "expires_on");

    private ComplianceExport() {
    }

    /** name: the file name before the date, e.g. "required-documents". */
    public static XlsExport.File xls(String name, Collection<Compliance> people, LocalDate today) {
        XlsExport sheet = XlsExport.sheet("Required documents", HEADER);
        for (Compliance compliance : people) {
            User user = compliance.user();
            Ship ship = user.getShip();
            for (RequiredDocument d : compliance.documents()) {
                sheet.row(Arrays.asList(ship == null ? null : ship.getImoNumber(), ship == null ? null : ship.getName(),
                        user.getFullName(), user.getRank() == null ? null : user.getRank().name(), d.type(),
                        d.optional() ? "no" : "yes", d.state().export(),
                        d.certificate() == null ? null : d.certificate().getExpiresOn()));
            }
        }
        return sheet.file(name + "-" + today);
    }
}
