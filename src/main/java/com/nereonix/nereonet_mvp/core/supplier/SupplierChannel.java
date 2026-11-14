package com.nereonix.nereonet_mvp.core.supplier;

import com.nereonix.nereonet_mvp.core.rfq.Rfq;

/**
 * How an RFQ reaches a supplier (P44): the seam between the RFQ screens and the outside world. RfqService picks the
 * bean whose type() is the supplier's Supplier.channel and never knows how it works. Only EmailChannel exists; a
 * marketplace or supplier API would be another implementation (and a ChannelType value) without changing the
 * screens. The office still enters the answers by hand (quote, decline): a channel that receives quotes by itself
 * would call RfqService the same way.
 * Every text a channel sends to a supplier is in English. Suppliers never see each other or each other's prices.
 */
public interface SupplierChannel {

    /** The channel this bean implements. */
    ChannelType type();

    /**
     * Sends the RFQ (its lines, port, delivery date and reply-by date) to one supplier, replies going to the office
     * user who sent it (Rfq.sentBy). ChannelException when it could not be delivered: the supplier shows Failed.
     */
    void sendRfq(Rfq rfq, Supplier supplier) throws ChannelException;

    /**
     * The RFQ was withdrawn (CANCELLED): tells this channel's suppliers who were still asked (sent or quoted) that it
     * no longer stands. Best effort: a failure is logged, never thrown.
     */
    void cancel(Rfq rfq);

    /**
     * The RFQ was awarded to another supplier: a short "not selected" message to this one. Best effort, as cancel.
     * (Not one of the two methods first planned for the seam, but awarding is a channel event too: a marketplace
     * would close the bid there.)
     */
    void notSelected(Rfq rfq, Supplier supplier);

    /** The RFQ could not be delivered; the message is for the office (the RFQ page's Failed row), never the supplier. */
    class ChannelException extends Exception {
        public ChannelException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
