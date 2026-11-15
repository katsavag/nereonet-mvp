package com.nereonix.nereonet_mvp.core.ai;

import java.util.ArrayList;
import java.util.List;

import com.nereonix.nereonet_mvp.core.ai.InvoiceReading.PageResult;

/**
 * Reads the photographed pages of a supplier's invoice into invoice lines, called by the server only
 * (core/order/InvoiceReadingService). The real one is LocalInvoiceReader (PaddleOCR on the server, nothing leaves
 * it); FakeInvoiceReader answers instead in dev (app.invoice.fake) and in the tests. Only the page images are read:
 * never a name of the crew, the order or anything else.
 */
public interface InvoiceReader {

    /** Pages can be read: the models are there (or the fake answers). */
    boolean configured();

    /** One photographed page: its number on board (1, 2, ...), the file's bytes and its content type (image/jpeg,
     *  image/png, image/webp, image/heic or application/pdf). */
    record Page(int number, byte[] content, String contentType) {
    }

    /**
     * What one page says: its table's lines and what its text says (supplier, invoice number, totals...). Never
     * null; a page without a table is a PageReading with tableFound false. InvoiceReadingException when the provider
     * fails, takes longer than its timeout or is not configured.
     */
    PageReading readPage(Page page);

    /** Every page in turn; a page that fails is a PageResult with its failure, the others keep their lines. */
    default InvoiceReading read(List<Page> pages) {
        List<PageResult> results = new ArrayList<>();
        for (Page page : pages) {
            try {
                results.add(new PageResult(page.number(), readPage(page), null));
            } catch (InvoiceReadingException e) {
                results.add(new PageResult(page.number(), null, e.reason()));
            }
        }
        return new InvoiceReading(List.copyOf(results));
    }
}
