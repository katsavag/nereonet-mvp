package com.nereonix.nereonet_mvp.core.order;

/**
 * Where the office stands with a received order's invoice (P64, ShipOrder.invoiceStatus). Null: the order has no read
 * invoice (none photographed, not read, or received before P64), and the P51 shortage flow applies.
 */
public enum InvoiceMatchStatus {
    /** Received with a read invoice: the office compares order, delivery and invoice, then marks it matched. */
    TO_MATCH,
    /** An office user marked it matched: every difference accepted, every item to review confirmed or changed. */
    MATCHED
}
