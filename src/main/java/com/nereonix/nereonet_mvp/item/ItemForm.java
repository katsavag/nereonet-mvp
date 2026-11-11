package com.nereonix.nereonet_mvp.item;

import com.nereonix.nereonet_mvp.core.item.Item;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// The add and edit item form.
public class ItemForm {

    @NotBlank(message = "{item.error.nameRequired}")
    @Size(max = Item.NAME_MAX_LENGTH, message = "{item.error.tooLong}")
    private String name;

    @NotBlank(message = "{item.error.unitRequired}")
    @Size(max = Item.UNIT_MAX_LENGTH, message = "{item.error.tooLong}")
    private String unit;

    @Size(max = Item.CATEGORY_MAX_LENGTH, message = "{item.error.tooLong}")
    private String category;

    static ItemForm of(Item item) {
        ItemForm form = new ItemForm();
        form.setName(item.getName());
        form.setUnit(item.getUnit());
        form.setCategory(item.getCategory());
        return form;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name == null ? null : name.strip(); }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit == null ? null : unit.strip(); }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category == null ? null : category.strip(); }
}
