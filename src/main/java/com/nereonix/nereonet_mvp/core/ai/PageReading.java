package com.nereonix.nereonet_mvp.core.ai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What one invoice page says (P62), as read, before any check (core/order/InvoiceChecks does those).
 * tableFound: a table of lines was found (by its header row, or structured by the chat model). The header fields are
 * null when the page does not show them: the invoice's supplier, number, date and the customer's order reference,
 * "page x of y", the page total, and on the last page usually net, VAT and total.
 */
public record PageReading(boolean tableFound, List<ReadLine> lines, String supplier, String invoiceNumber,
                          LocalDate date, String orderReference, Integer pageNumber, Integer pageCount,
                          BigDecimal pageTotal, BigDecimal net, BigDecimal vat, BigDecimal total) {

    /**
     * One line of the table as read. no: the line number (No, Α/Α); a number is null when its cell is empty or not a
     * number that can be read. raw: the row as the OCR gave it. confidence: from 0 to 1, how sure the OCR is of the
     * block the line is in; null when it did not say.
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

    public PageReading withLines(List<ReadLine> newLines) {
        return new PageReading(tableFound, List.copyOf(newLines), supplier, invoiceNumber, date, orderReference,
                pageNumber, pageCount, pageTotal, net, vat, total);
    }

    public PageReading withOrderReference(String reference) {
        return new PageReading(tableFound, lines, supplier, invoiceNumber, date, reference, pageNumber, pageCount,
                pageTotal, net, vat, total);
    }
}
