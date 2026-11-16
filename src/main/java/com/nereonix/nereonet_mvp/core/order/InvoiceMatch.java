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
    /**
     * Nothing likely: a row of its own, counted as the invoice has it ("On the invoice, not on the order", a new
     * item). The cook may say it is an item the vessel has (CHOSEN) or that it was not ordered (NOT_ORDERED).
     */
    PICK,
    /** The cook chose the product, or the Master's approval made the line a new item of the vessel. */
    CHOSEN,
    /** The cook chose "Not ordered": never stocked, it goes to the office. */
    NOT_ORDERED;

    /** Linked to an order line or an item: counted as a row. */
    public boolean linked() {
        return this == MATCHED || this == CHECK || this == LEARNED || this == CHOSEN;
    }
}
