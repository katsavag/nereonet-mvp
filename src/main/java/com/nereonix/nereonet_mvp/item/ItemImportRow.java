package com.nereonix.nereonet_mvp.item;

import com.nereonix.nereonet_mvp.core.importfile.ImportRow;

// One item on the import review screen. Values stay strings so a wrong value from the file can be shown.
// The action (ImportRow.getAction) is set by ItemImportService.validate: a name the ship does not have is a new item;
// a name it has updates that item's unit and category, or changes nothing when they are the same.
public class ItemImportRow extends ImportRow {

    private String name = "";
    private String unit = "";
    private String category = "";

    public String getName() { return name; }
    public void setName(String name) { this.name = name == null ? "" : name.strip(); }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit == null ? "" : unit.strip(); }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category == null ? "" : category.strip(); }
}
