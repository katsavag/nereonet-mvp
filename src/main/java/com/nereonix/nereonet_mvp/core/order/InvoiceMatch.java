package com.nereonix.nereonet_mvp.core.order;

/**
 * How a line read from the invoice was matched to the order (DeliveryInvoiceLine.match). Null until matched
 * (InvoiceMatchService.ensureMatched, when the count or the approval opens).
 */
public enum InvoiceMatch {
    /** One clear best order line (or vessel item), well ahead of the next: linked automatically. */
    MATCHED,
    /** A likely order line (or item), not clearly ahead: linked, and marked "match to review" for the office. */
    CHECK,
    /** The supplier's description was matched before (SupplierItemName): linked automatically. */
    LEARNED,
    /** Nothing likely: the cook chooses the product ("On the invoice, not found on the order"). */
    PICK,
    /** The cook chose the product. */
    CHOSEN,
    /** The cook chose "Not ordered": never stocked, it goes to the office. */
    NOT_ORDERED;

    /** Linked to an order line or an item: counted as a row. */
    public boolean linked() {
        return this == MATCHED || this == CHECK || this == LEARNED || this == CHOSEN;
    }
}
