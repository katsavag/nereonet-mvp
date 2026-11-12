package com.nereonix.nereonet_mvp.item;

import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// The add and edit item form. The place is shown only for Provisions; ItemService drops it for personal items.
public class ItemForm {

    @NotBlank(message = "{item.error.nameRequired}")
    @Size(max = Item.NAME_MAX_LENGTH, message = "{item.error.tooLong}")
    private String name;

    @NotBlank(message = "{item.error.unitRequired}")
    @Size(max = Item.UNIT_MAX_LENGTH, message = "{item.error.tooLong}")
    private String unit;

    @NotNull(message = "{item.error.categoryRequired}")
    private ItemCategory category;

    @Size(max = Item.PLACE_MAX_LENGTH, message = "{item.error.tooLong}")
    private String place;

    static ItemForm of(Item item) {
        ItemForm form = new ItemForm();
        form.setName(item.getName());
        form.setUnit(item.getUnit());
        form.setCategory(item.getCategory());
        form.setPlace(item.getPlace());
        return form;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name == null ? null : name.strip(); }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit == null ? null : unit.strip(); }

    public ItemCategory getCategory() { return category; }
    public void setCategory(ItemCategory category) { this.category = category; }

    public String getPlace() { return place; }
    public void setPlace(String place) { this.place = place == null ? null : place.strip(); }
}
