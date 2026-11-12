package com.nereonix.nereonet_mvp.officeorder;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import com.nereonix.nereonet_mvp.core.order.ShipOrder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// The office's "Confirm delivery" form on /office/orders/{id} (P36): both fields required. The port starts as the
// requested port (or the confirmed one, when correcting).
public class ConfirmOrderForm {

    @NotBlank(message = "{officeorder.confirm.error.portRequired}")
    @Size(max = ShipOrder.PORT_MAX_LENGTH, message = "{officeorder.confirm.error.tooLong}")
    private String confirmedPort;

    @NotNull(message = "{officeorder.confirm.error.dateRequired}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate deliveryDate;

    public String getConfirmedPort() { return confirmedPort; }
    public void setConfirmedPort(String confirmedPort) {
        this.confirmedPort = confirmedPort == null ? null : confirmedPort.strip();
    }

    public LocalDate getDeliveryDate() { return deliveryDate; }
    public void setDeliveryDate(LocalDate deliveryDate) { this.deliveryDate = deliveryDate; }

    /** The form as the order page shows it: the confirmed port and date once set, else the requested port. */
    static ConfirmOrderForm of(ShipOrder order) {
        ConfirmOrderForm form = new ConfirmOrderForm();
        form.setConfirmedPort(order.getConfirmedPort() != null ? order.getConfirmedPort() : order.getRequestedPort());
        form.setDeliveryDate(order.getDeliveryDate());
        return form;
    }
}
