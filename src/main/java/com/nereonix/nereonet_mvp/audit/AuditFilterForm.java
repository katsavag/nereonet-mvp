package com.nereonix.nereonet_mvp.audit;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import com.nereonix.nereonet_mvp.core.form.PlausibleDate;

// Filters of the audit log page (GET). Every field is optional. Dates are UTC days, both inclusive. The search, sort
// and page are the shared ListRequest (P19).
public class AuditFilterForm {

    private Long shipId;

    @PlausibleDate(yearsBefore = 30, yearsAfter = 1)
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate from;

    @PlausibleDate(yearsBefore = 30, yearsAfter = 1)
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate to;

    public Long getShipId() { return shipId; }
    public void setShipId(Long shipId) { this.shipId = shipId; }

    public LocalDate getFrom() { return from; }
    public void setFrom(LocalDate from) { this.from = from; }

    public LocalDate getTo() { return to; }
    public void setTo(LocalDate to) { this.to = to; }
}
