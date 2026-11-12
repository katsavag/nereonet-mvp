package com.nereonix.nereonet_mvp.certificate;

import java.util.List;

import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.user.User;

/**
 * A ship's certificate matrix (P20), the ship page's Certificates tab: who holds which certificate.
 * types  the columns: the certificate types held by the ship's crew (spellings that differ only in case or spaces are
 *        one column), the most held first, then by name
 * rows   the active crew on board, by department and rank (Rank's order; without a rank last), then by name
 * Each cell is the certificate of that type that expires last (the current one), or null when there is none.
 */
public record CertificateMatrix(List<String> types, List<Row> rows) {

    public record Row(User member, List<Cell> cells) {
    }

    public record Cell(String type, Certificate certificate) {
    }

    /** Number of cells with a certificate. */
    public long held() {
        return rows.stream().flatMap(row -> row.cells().stream()).filter(cell -> cell.certificate() != null).count();
    }
}
