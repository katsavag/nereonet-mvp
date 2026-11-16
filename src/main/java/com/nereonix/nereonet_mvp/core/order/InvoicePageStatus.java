package com.nereonix.nereonet_mvp.core.order;

/**
 * Where the reading of one photographed invoice page stands (OrderReceiptFile.invoicePageStatus). The three
 * failures are the reader's (InvoiceReadingException.Reason); HARD_TO_READ is a page that was read but showed no
 * table, or fewer lines than its line numbers suggest.
 */
public enum InvoicePageStatus {
    /** Photographed, not read yet (or to be read again). */
    WAITING,
    READ,
    HARD_TO_READ,
    TIMEOUT,
    PROVIDER_ERROR,
    NOT_CONFIGURED;

    /** The reader could not read it (no answer, an error, no key). */
    public boolean failed() {
        return this == TIMEOUT || this == PROVIDER_ERROR || this == NOT_CONFIGURED;
    }
}
