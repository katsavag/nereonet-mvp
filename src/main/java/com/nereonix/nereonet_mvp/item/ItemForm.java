package com.nereonix.nereonet_mvp.item;

import java.math.BigDecimal;

import com.nereonix.nereonet_mvp.core.form.PlausibleQuantity;
import com.nereonix.nereonet_mvp.core.form.QuantityRules;
import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// The add and edit item form. The place is shown only for Provisions; ItemService drops it for personal items.
// MaxPerRequest, optional, the most one request or order line may hold ("2,5" is 2.5), with the decimals of the
// unit (whole numbers for pcs); it replaces the unit's maximum (core/form/QuantityRules).
@PlausibleQuantity(quantity = "maxPerRequest", unit = "unit", limit = false)
public class ItemForm {

    @NotBlank(message = "{item.error.nameRequired}")
    @Size(max = Item.NAME_MAX_LENGTH, message = "{item.error.tooLong}")
    private String name;

    // Chosen from the list (ItemUnits); ItemController refuses any other unit, except the one the item already has.
    @NotBlank(message = "{unit.error.notListed}")
    @Size(max = Item.UNIT_MAX_LENGTH, message = "{item.error.tooLong}")
    private String unit;

    @NotNull(message = "{item.error.categoryRequired}")
    private ItemCategory category;

    @Size(max = Item.PLACE_MAX_LENGTH, message = "{item.error.tooLong}")
    private String place;

    // Text, so "2,5" and a wrong value come back as typed; at most 13 characters (9 digits, a dot, 3 decimals).
    @Size(max = 13, message = "{item.error.tooLong}")
    private String maxPerRequest;

    static ItemForm of(Item item) {
        ItemForm form = new ItemForm();
        form.setName(item.getName());
        form.setUnit(item.getUnit());
        form.setCategory(item.getCategory());
        form.setPlace(item.getPlace());
        form.setMaxPerRequest(item.getMaxPerRequest() == null ? null
                : item.getMaxPerRequest().stripTrailingZeros().toPlainString());
        return form;
    }

    /** The maximum per request as a number (checked by @PlausibleQuantity); null when not set. */
    BigDecimal maxPerRequestValue() {
        return maxPerRequest == null || maxPerRequest.isEmpty() ? null
                : new BigDecimal(maxPerRequest.replace(',', '.')).stripTrailingZeros();
    }

    /** The rule of the unit as typed, for the hint (the unit's own maximum). */
    public QuantityRules.Kind unitKind() {
        return QuantityRules.kindOf(unit);
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name == null ? null : name.strip(); }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit == null ? null : unit.strip(); }

    public ItemCategory getCategory() { return category; }
    public void setCategory(ItemCategory category) { this.category = category; }

    public String getPlace() { return place; }
    public void setPlace(String place) { this.place = place == null ? null : place.strip(); }

    public String getMaxPerRequest() { return maxPerRequest; }
    public void setMaxPerRequest(String maxPerRequest) {
        this.maxPerRequest = maxPerRequest == null ? null : maxPerRequest.strip();
    }
}
