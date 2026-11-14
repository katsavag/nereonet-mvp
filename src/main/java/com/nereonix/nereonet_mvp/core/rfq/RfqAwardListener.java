package com.nereonix.nereonet_mvp.core.rfq;

import com.nereonix.nereonet_mvp.core.user.User;

/**
 * The seam for what follows an award (P44): placing the purchase order with the chosen supplier. Out of scope for
 * now, so there is no implementation. A bean implementing this is called by RfqService.award inside its
 * transaction, after the RFQ is AWARDED and the other suppliers were told; throwing rolls the award back.
 * The quote has everything a purchase order needs: supplier, currency, lines with prices and pack sizes, delivery
 * cost and date; the RFQ has the port and the vessel order.
 */
public interface RfqAwardListener {

    void awarded(Rfq rfq, RfqQuote quote, User officeUser);
}
