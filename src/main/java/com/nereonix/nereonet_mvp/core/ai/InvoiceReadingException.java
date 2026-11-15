package com.nereonix.nereonet_mvp.core.ai;

/**
 * Reading an invoice page failed. The message names only what went wrong (the reason, a status code), never
 * the key, the image or the text.
 */
public class InvoiceReadingException extends RuntimeException {

    /** TIMEOUT: no answer within app.mistral.ocr-timeout. PROVIDER_ERROR: anything else (a status, no connection).
     *  NOT_CONFIGURED: no key. A page without a table is not a failure: it is a PageReading without lines. */
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
