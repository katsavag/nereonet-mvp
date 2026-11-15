package com.nereonix.nereonet_mvp.core.ai;

import java.util.List;

/** What InvoiceReader.read made of the pages: one result per page, in the order sent. */
public record InvoiceReading(List<PageResult> pages) {

    /** One page: what it says, or why it could not be read (reading null). */
    public record PageResult(int number, PageReading reading, InvoiceReadingException.Reason failure) {
    }
}
