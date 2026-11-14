package com.nereonix.nereonet_mvp.core.rfq;

// The life of a request for quotes (P44). An order has at most one OPEN RFQ at a time; AWARDED and CANCELLED ones
// stay as its history. Labels: rfq.status.<NAME>.
public enum RfqStatus {
    // Sent to the suppliers, collecting their quotes.
    OPEN,
    // One quote chosen for the whole RFQ.
    AWARDED,
    // Withdrawn by the office; the suppliers still waiting were told.
    CANCELLED
}
