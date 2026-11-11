package com.nereonix.nereonet_mvp.item;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

// One item on the import review screen. Values stay strings so a wrong value from the file can be shown.
public class ItemImportRow implements Serializable {

    // The row's number in the file. Not editable.
    private int rowNumber;

    private String name = "";
    private String unit = "";
    private String category = "";
    private boolean excluded;

    // Set by ItemImportService.validate: the ship already has an item with this name, so the row is skipped.
    private boolean existing;

    // Field name -> message key. Set by ItemImportService.validate; empty for excluded and existing rows.
    private final Map<String, String> errors = new HashMap<>();

    public boolean hasErrors() { return !errors.isEmpty(); }

    public int getRowNumber() { return rowNumber; }
    public void setRowNumber(int rowNumber) { this.rowNumber = rowNumber; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name == null ? "" : name.strip(); }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit == null ? "" : unit.strip(); }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category == null ? "" : category.strip(); }

    public boolean isExcluded() { return excluded; }
    public void setExcluded(boolean excluded) { this.excluded = excluded; }

    public boolean isExisting() { return existing; }
    public void setExisting(boolean existing) { this.existing = existing; }

    public Map<String, String> getErrors() { return errors; }
}
