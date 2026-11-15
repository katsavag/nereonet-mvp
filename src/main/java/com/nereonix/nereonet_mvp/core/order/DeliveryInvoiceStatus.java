package com.nereonix.nereonet_mvp.core.order;

/** Where the reading of an order's invoice stands (P62, DeliveryInvoice.status). */
public enum DeliveryInvoiceStatus {
    /** Its pages are being read (InvoiceReadingService). */
    READING,
    /** Every page was read. */
    READ,
    /** Some pages were read, others failed or were hard to read. */
    PARTLY_READ,
    /** No line was read: no page could be read, or none showed a table. */
    FAILED
}
