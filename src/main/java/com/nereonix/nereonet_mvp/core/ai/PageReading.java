package com.nereonix.nereonet_mvp.core.ai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * What one invoice page says, as read, before any check (core/order/InvoiceChecks does those).
 * tableFound: a table of lines was found (by its header row). The header fields are
 * null when the page does not show them: the invoice's supplier, number, date and the customer's order reference,
 * "page x of y", the page total, and on the last page usually net, VAT and total.
 */
public record PageReading(boolean tableFound, List<ReadLine> lines, String supplier, String invoiceNumber,
                          LocalDate date, String orderReference, Integer pageNumber, Integer pageCount,
                          BigDecimal pageTotal, BigDecimal net, BigDecimal vat, BigDecimal total) {

    /**
     * One line of the table as read. no: the line number (No, Α/Α); a number is null when its cell is empty or not a
     * number that can be read. raw: the row's cells as the reader gave them. confidence: from 0 to 1, how sure the
     * reader is of the line's numbers; null when it did not say.
     */
    public record ReadLine(Integer no, String description, String unit, BigDecimal quantity, BigDecimal unitPrice,
                           BigDecimal amount, String raw, Double confidence) {

        public ReadLine withConfidence(Double value) {
            return new ReadLine(no, description, unit, quantity, unitPrice, amount, raw, value);
        }
    }

    public static PageReading noTable(String supplier, String invoiceNumber, LocalDate date, String orderReference,
                                      Integer pageNumber, Integer pageCount) {
        return new PageReading(false, List.of(), supplier, invoiceNumber, date, orderReference, pageNumber, pageCount,
                null, null, null, null);
    }

    /**
     * Several readings (the pages of one PDF) as one: the lines one after the other, the first value of each field
     * (the totals: the last).
     */
    public static PageReading merge(List<PageReading> readings) {
        if (readings.isEmpty()) {
            return noTable(null, null, null, null, null, null);
        }
        if (readings.size() == 1) {
            return readings.getFirst();
        }
        List<ReadLine> lines = new ArrayList<>();
        boolean found = false;
        String supplier = null;
        String number = null;
        LocalDate date = null;
        String reference = null;
        Integer page = null;
        Integer pages = null;
        BigDecimal pageTotal = null;
        BigDecimal net = null;
        BigDecimal vat = null;
        BigDecimal total = null;
        for (PageReading r : readings) {
            found |= r.tableFound();
            lines.addAll(r.lines());
            supplier = supplier != null ? supplier : r.supplier();
            number = number != null ? number : r.invoiceNumber();
            date = date != null ? date : r.date();
            reference = reference != null ? reference : r.orderReference();
            page = page != null ? page : r.pageNumber();
            pages = pages != null ? pages : r.pageCount();
            pageTotal = r.pageTotal() != null ? r.pageTotal() : pageTotal;
            net = r.net() != null ? r.net() : net;
            vat = r.vat() != null ? r.vat() : vat;
            total = r.total() != null ? r.total() : total;
        }
        return new PageReading(found, List.copyOf(lines), supplier, number, date, reference, page, pages, pageTotal,
                net, vat, total);
    }

    public PageReading withLines(List<ReadLine> newLines) {
        return new PageReading(tableFound, List.copyOf(newLines), supplier, invoiceNumber, date, orderReference,
                pageNumber, pageCount, pageTotal, net, vat, total);
    }

    public PageReading withOrderReference(String reference) {
        return new PageReading(tableFound, lines, supplier, invoiceNumber, date, reference, pageNumber, pageCount,
                pageTotal, net, vat, total);
    }
}
