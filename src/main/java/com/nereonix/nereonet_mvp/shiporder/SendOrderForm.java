package com.nereonix.nereonet_mvp.shiporder;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import com.nereonix.nereonet_mvp.core.form.PlausibleDate;
import com.nereonix.nereonet_mvp.core.order.ShipOrder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// The "Send to office" form of /ship/order (P35). Port and date are required; the date from today (UTC) to two years
// ahead (P39: @PlausibleDate, which also refuses a past date, as ShipOrderService.send does).
public class SendOrderForm {

    @NotBlank(message = "{order.send.error.portRequired}")
    @Size(max = ShipOrder.PORT_MAX_LENGTH, message = "{order.send.error.tooLong}")
    private String requestedPort;

    @NotNull(message = "{order.send.error.neededByRequired}")
    @PlausibleDate(yearsBefore = 0, yearsAfter = 2)
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate neededBy;

    @Size(max = ShipOrder.NOTE_MAX_LENGTH, message = "{order.send.error.tooLong}")
    private String officeNote;

    public String getRequestedPort() { return requestedPort; }
    public void setRequestedPort(String requestedPort) {
        this.requestedPort = requestedPort == null ? null : requestedPort.strip();
    }

    public LocalDate getNeededBy() { return neededBy; }
    public void setNeededBy(LocalDate neededBy) { this.neededBy = neededBy; }

    public String getOfficeNote() { return officeNote; }
    public void setOfficeNote(String officeNote) { this.officeNote = officeNote == null ? null : officeNote.strip(); }
}
