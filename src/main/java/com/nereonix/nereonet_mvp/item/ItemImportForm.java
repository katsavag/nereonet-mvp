package com.nereonix.nereonet_mvp.item;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

// The import review screen: every item from the uploaded file, for one ship. Kept in the HTTP session
// until the import is submitted, and the edited values are bound onto it (see ItemImportController).
public class ItemImportForm implements Serializable {

    // The ship the file was uploaded for. Null when there is no upload in the session.
    private Long shipId;

    private List<ItemImportRow> rows = new ArrayList<>();

    public int getErrorCount() {
        return (int) rows.stream().filter(row -> !row.isExcluded() && row.hasErrors()).count();
    }

    public int getExcludedCount() {
        return (int) rows.stream().filter(ItemImportRow::isExcluded).count();
    }

    // Included rows whose name the ship already has.
    public List<ItemImportRow> getSkippedRows() {
        return rows.stream().filter(row -> !row.isExcluded() && row.isExisting()).toList();
    }

    public List<ItemImportRow> getRowsToAdd() {
        return rows.stream().filter(row -> !row.isExcluded() && !row.isExisting()).toList();
    }

    // Valid after ItemImportService.validate: something to add and no errors.
    public boolean isReady() {
        return getErrorCount() == 0 && !getRowsToAdd().isEmpty();
    }

    public Long getShipId() { return shipId; }
    public void setShipId(Long shipId) { this.shipId = shipId; }

    public List<ItemImportRow> getRows() { return rows; }
    public void setRows(List<ItemImportRow> rows) { this.rows = rows; }
}
