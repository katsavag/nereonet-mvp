package com.nereonix.nereonet_mvp.audit;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

// Filters of the audit log page (GET). Every field is optional. Dates are UTC days, both inclusive.
public class AuditFilterForm {

    private Long shipId;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate from;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate to;

    // Zero-based. Not a form field: set by the pagination links.
    private int page;

    public Long getShipId() { return shipId; }
    public void setShipId(Long shipId) { this.shipId = shipId; }

    public LocalDate getFrom() { return from; }
    public void setFrom(LocalDate from) { this.from = from; }

    public LocalDate getTo() { return to; }
    public void setTo(LocalDate to) { this.to = to; }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
}
