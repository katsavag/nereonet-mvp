package com.nereonix.nereonet_mvp.core.rfq;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import com.nereonix.nereonet_mvp.core.export.XlsExport;

/**
 * The RFQ as an Excel 97-2003 workbook (P44), attached to the email to each supplier: the export conventions of P39
 * and P40 (XlsExport: one sheet, bold frozen header with an autofilter, date cells, numbers as numbers), sheet "RFQ",
 * one row per line by category and item name, with empty columns for the supplier's answer. The column names are a
 * file format, never translated.
 */
public final class RfqWorkbook {

    static final List<String> HEADER = List.of("rfq", "vessel", "imo_number", "port", "delivery_date", "reply_by",
            "category", "item_name", "unit", "quantity", "unit_price", "quantity_offered", "pack_size", "pack_unit",
            "note");

    private RfqWorkbook() {
    }

    /** rfq-12.xls. */
    public static XlsExport.File of(Rfq rfq) {
        XlsExport sheet = XlsExport.sheet("RFQ", HEADER);
        for (RfqLine line : byCategoryAndName(rfq.getLines())) {
            sheet.row(Arrays.asList(rfq.number(), rfq.getOrder().getShip().getName(),
                    rfq.getOrder().getShip().getImoNumber(), rfq.getPort(), rfq.getDeliveryDate(), rfq.getReplyBy(),
                    line.getCategory().fileValue(), line.getItemName(), line.getUnit(), line.getQuantity(),
                    null, null, null, null, null));
        }
        return sheet.file(fileBaseName(rfq));
    }

    static String fileBaseName(Rfq rfq) {
        return "rfq-" + rfq.getId();
    }

    /** The lines by category (Provisions, then Personal items), then item name: every RFQ list's order. */
    public static List<RfqLine> byCategoryAndName(List<RfqLine> lines) {
        return lines.stream()
                .sorted(Comparator.comparing(RfqLine::getCategory)
                        .thenComparing(RfqLine::getItemName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(RfqLine::getId))
                .toList();
    }
}
