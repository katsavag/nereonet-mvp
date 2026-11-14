package com.nereonix.nereonet_mvp.rfq;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;

import com.nereonix.nereonet_mvp.core.form.PlausibleDate;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;
import com.nereonix.nereonet_mvp.core.rfq.Rfq;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// "Request quotes" on a vessel order (P44): the port and delivery date (prefilled from the order's requested port
// and needed by date), the reply-by date and the suppliers to ask (at least one). Shown again on the review page in
// hidden fields. Dates follow the P39 plausible-date rule.
public class RfqForm {

    /** The reply-by date starts this many days from today (not after the delivery date). */
    static final int DEFAULT_REPLY_DAYS = 3;

    @NotBlank(message = "{rfq.error.portRequired}")
    @Size(max = Rfq.PORT_MAX_LENGTH, message = "{rfq.error.tooLong}")
    private String port;

    @NotNull(message = "{rfq.error.deliveryDateRequired}")
    @PlausibleDate(yearsBefore = 0, yearsAfter = 2)
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate deliveryDate;

    @NotNull(message = "{rfq.error.replyByRequired}")
    @PlausibleDate(yearsBefore = 0, yearsAfter = 1)
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate replyBy;

    @NotEmpty(message = "{rfq.error.suppliersRequired}")
    private List<Long> supplierIds = new ArrayList<>();

    /** The answers are needed before the delivery: reply by on or before the delivery date. */
    @AssertTrue(message = "{rfq.error.replyByAfterDelivery}")
    public boolean isReplyByInTime() {
        return replyBy == null || deliveryDate == null || !replyBy.isAfter(deliveryDate);
    }

    /** The form as it opens: the order's requested port and needed by date, reply by in three days. */
    static RfqForm of(ShipOrder order, LocalDate today) {
        RfqForm form = new RfqForm();
        form.setPort(order.getRequestedPort());
        LocalDate delivery = order.getNeededBy() != null && !order.getNeededBy().isBefore(today) ? order.getNeededBy()
                : null;
        form.setDeliveryDate(delivery);
        LocalDate reply = today.plusDays(DEFAULT_REPLY_DAYS);
        form.setReplyBy(delivery != null && reply.isAfter(delivery) ? delivery : reply);
        return form;
    }

    public String getPort() { return port; }
    public void setPort(String port) { this.port = port == null ? null : port.strip(); }
    public LocalDate getDeliveryDate() { return deliveryDate; }
    public void setDeliveryDate(LocalDate deliveryDate) { this.deliveryDate = deliveryDate; }
    public LocalDate getReplyBy() { return replyBy; }
    public void setReplyBy(LocalDate replyBy) { this.replyBy = replyBy; }
    public List<Long> getSupplierIds() { return supplierIds; }
    public void setSupplierIds(List<Long> supplierIds) {
        this.supplierIds = supplierIds == null ? new ArrayList<>() : supplierIds;
    }
}
