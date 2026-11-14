package com.nereonix.nereonet_mvp.stores;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;

import com.nereonix.nereonet_mvp.core.form.PlausibleDate;
import com.nereonix.nereonet_mvp.core.stock.StockMovementType;

// Filters of the store's history (P53, GET /ship/stores/history). Every field is optional. type: COUNT or RECEIPT
// (anything else: all); dates are UTC days, both included, as the audit log's.
public class StoresHistoryFilter {

    private String type;

    @PlausibleDate(yearsBefore = 30, yearsAfter = 1)
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate from;

    @PlausibleDate(yearsBefore = 30, yearsAfter = 1)
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate to;

    private Integer page;

    /** The type chosen, or null for all (and for an unknown value). */
    public StockMovementType movementType() {
        for (StockMovementType value : StockMovementType.values()) {
            if (value.name().equals(type)) {
                return value;
            }
        }
        return null;
    }

    /** The page, from 0. */
    public int pageIndex() {
        return page == null || page < 0 ? 0 : page;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public LocalDate getFrom() { return from; }
    public void setFrom(LocalDate from) { this.from = from; }

    public LocalDate getTo() { return to; }
    public void setTo(LocalDate to) { this.to = to; }

    public Integer getPage() { return page; }
    public void setPage(Integer page) { this.page = page; }
}
