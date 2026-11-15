package com.nereonix.nereonet_mvp.shiprequest;

import java.util.ArrayList;
import java.util.List;

import com.nereonix.nereonet_mvp.core.item.Item;

import jakarta.validation.constraints.Size;

/**
 * "New request" and "Edit request" (P54). lines: one per item the person can pick, in the order of the page
 * (ShipRequestService.form), with the item's id and the quantity typed: blank means not picked. Quantities are text,
 * so "2,5" is read as 2.5 (P60: core/form/QuantityRules, the rule of the item's unit). The Chief cook only: newItems, the items not on the list
 * already added to the request, and newName, newUnit and newQuantity, the fields of "Add an item that is not on the
 * list". The checks are ShipRequestService's (it reports them into a BindingResult); the @Size limits are here for the
 * counters (layout/parts :: counter) and are checked there too.
 */
public class RequestForm {

    private List<Line> lines = new ArrayList<>();

    private List<NewLine> newItems = new ArrayList<>();

    @Size(max = Item.NAME_MAX_LENGTH)
    private String newName;

    @Size(max = Item.UNIT_MAX_LENGTH)
    private String newUnit;

    private String newQuantity;

    /** One item of the ship's list; quantity blank when not picked. */
    public static class Line {

        private Long itemId;
        private String quantity;

        public Line() {
        }

        Line(Long itemId, String quantity) {
            this.itemId = itemId;
            this.quantity = quantity;
        }

        public Long getItemId() { return itemId; }
        public void setItemId(Long itemId) { this.itemId = itemId; }
        public String getQuantity() { return quantity; }
        public void setQuantity(String quantity) { this.quantity = quantity == null ? null : quantity.strip(); }

        public boolean isPicked() {
            return quantity != null && !quantity.isEmpty();
        }
    }

    /** An item that is not on the list (the Chief cook only), by name and optional unit. */
    public static class NewLine {

        private String name;
        private String unit;
        private String quantity;

        public NewLine() {
        }

        NewLine(String name, String unit, String quantity) {
            this.name = name;
            this.unit = unit;
            this.quantity = quantity;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name == null ? null : name.strip(); }
        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit == null || unit.isBlank() ? null : unit.strip(); }
        public String getQuantity() { return quantity; }
        public void setQuantity(String quantity) { this.quantity = quantity == null ? null : quantity.strip(); }
    }

    /** True when something is typed in "Add an item that is not on the list". */
    public boolean hasNewItemTyped() {
        return (newName != null && !newName.isEmpty()) || newUnit != null
                || (newQuantity != null && !newQuantity.isEmpty());
    }

    void clearNewItemFields() {
        newName = null;
        newUnit = null;
        newQuantity = null;
    }

    public List<Line> getLines() { return lines; }
    public void setLines(List<Line> lines) { this.lines = lines == null ? new ArrayList<>() : lines; }
    public List<NewLine> getNewItems() { return newItems; }
    public void setNewItems(List<NewLine> newItems) { this.newItems = newItems == null ? new ArrayList<>() : newItems; }
    public String getNewName() { return newName; }
    public void setNewName(String newName) { this.newName = newName == null ? null : newName.strip(); }
    public String getNewUnit() { return newUnit; }
    public void setNewUnit(String newUnit) { this.newUnit = newUnit == null || newUnit.isBlank() ? null : newUnit.strip(); }
    public String getNewQuantity() { return newQuantity; }
    public void setNewQuantity(String newQuantity) { this.newQuantity = newQuantity == null ? null : newQuantity.strip(); }
}
