package com.nereonix.nereonet_mvp.core.order;

/**
 * The Chief cook's count of a delivery, kept on a CONFIRMED order (ShipOrder.deliveryCount; null in the
 * database is NONE, for orders from before P61 and orders nobody counted). A count only helps the Master: it never
 * blocks Confirm receipt.
 */
public enum DeliveryCountStatus {
    /** Nobody counted yet. */
    NONE,
    /** The cook saved a count and may come back to it. */
    DRAFT,
    /** The cook sent it: the Master approves it by confirming receipt, or sends it back. */
    SENT_TO_MASTER,
    /** The Master sent it back with a note: a draft again, the cook's numbers kept. */
    RETURNED,
    /** The Master confirmed receipt of a count sent to them: the cook is the receipt's "counted by". */
    APPROVED;

    /** The cook can still change the count: NONE, DRAFT or RETURNED. */
    public boolean editable() {
        return this == NONE || this == DRAFT || this == RETURNED;
    }
}
