package com.nereonix.nereonet_mvp.core.rfq;

// Where one supplier of an RFQ stands (P44). "No reply" is not stored: a SENT supplier once the reply-by date has
// passed (RfqQuote.state). Labels: rfq.supplierStatus.<state>.
public enum QuoteStatus {
    // The RFQ reached the supplier's channel; no answer entered yet.
    SENT,
    // The office entered the supplier's quote.
    QUOTED,
    // The supplier said no (the office recorded it, with an optional reason).
    DECLINED,
    // The channel could not deliver the RFQ (e.g. the email was refused); it can be sent again.
    FAILED
}
