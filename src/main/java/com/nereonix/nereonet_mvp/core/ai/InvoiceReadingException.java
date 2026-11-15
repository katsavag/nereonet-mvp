package com.nereonix.nereonet_mvp.core.ai;

/**
 * Reading an invoice page failed. The message names only what went wrong (the reason, a status code), never
 * the image or the text.
 */
public class InvoiceReadingException extends RuntimeException {

    /** TIMEOUT: the reader took too long (FakeInvoiceReader can say so). PROVIDER_ERROR: the OCR failed or the page
     *  cannot be decoded. NOT_CONFIGURED: the OCR models are missing. A page without a table is not a failure: it is
     *  a PageReading without lines. */
    public enum Reason {
        TIMEOUT, PROVIDER_ERROR, NOT_CONFIGURED
    }

    private final Reason reason;

    public InvoiceReadingException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
