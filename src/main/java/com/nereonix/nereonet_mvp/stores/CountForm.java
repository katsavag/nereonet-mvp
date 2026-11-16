package com.nereonix.nereonet_mvp.stores;

import java.util.LinkedHashMap;
import java.util.Map;

import com.nereonix.nereonet_mvp.core.item.Item;

import jakarta.validation.constraints.Size;

/**
 * "Count the inventory", one form for the whole page. quantities: by item id, the quantity typed in its row (text, so
 * "2,5" is read as 2.5); shown: by item id, the quantity the page showed when it was opened (a hidden field), so a row
 * left as it was is told from a row that was changed, even when the inventory changed meanwhile. newName, newUnit,
 * newPlace and newQuantity: the fields of "Add an item that is not on the list". The checks are StoresController's;
 * the @Size limits are here for the counters (layout/parts :: counter) and are checked there too.
 */
public class CountForm {

    private Map<Long, String> quantities = new LinkedHashMap<>();

    private Map<Long, String> shown = new LinkedHashMap<>();

    @Size(max = Item.NAME_MAX_LENGTH)
    private String newName;

    @Size(max = Item.UNIT_MAX_LENGTH)
    private String newUnit;

    @Size(max = Item.PLACE_MAX_LENGTH)
    private String newPlace;

    private String newQuantity;

    /** True when a field of the new item is filled: then its name, unit and quantity are required. */
    public boolean hasNewItem() {
        return newName != null || newUnit != null || newPlace != null || newQuantity != null;
    }

    private static String typed(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    public Map<Long, String> getQuantities() { return quantities; }
    public void setQuantities(Map<Long, String> quantities) { this.quantities = quantities; }
    public Map<Long, String> getShown() { return shown; }
    public void setShown(Map<Long, String> shown) { this.shown = shown; }
    public String getNewName() { return newName; }
    public void setNewName(String newName) { this.newName = typed(newName); }
    public String getNewUnit() { return newUnit; }
    public void setNewUnit(String newUnit) { this.newUnit = typed(newUnit); }
    public String getNewPlace() { return newPlace; }
    public void setNewPlace(String newPlace) { this.newPlace = typed(newPlace); }
    public String getNewQuantity() { return newQuantity; }
    public void setNewQuantity(String newQuantity) { this.newQuantity = typed(newQuantity); }
}
