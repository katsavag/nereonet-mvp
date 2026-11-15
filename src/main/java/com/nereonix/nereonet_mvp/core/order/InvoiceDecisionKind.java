package com.nereonix.nereonet_mvp.core.order;

/** What the office decided about one point of an invoice comparison (P64, InvoiceDecision.kind). */
public enum InvoiceDecisionKind {
    /** A difference accepted, with a note ("Credit note requested from the supplier"). */
    ACCEPTED,
    /** An item to review confirmed as it is (Correct). */
    CONFIRMED,
    /** An item to review changed: another product or factor, or a value corrected. */
    CHANGED
}
