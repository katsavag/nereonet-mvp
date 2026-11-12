package com.nereonix.nereonet_mvp.core.item;

import java.util.Arrays;

import com.nereonix.nereonet_mvp.core.importfile.ImportNames;

// What an item is for. Labels: item.category.<NAME> in messages.properties; the office pages show them in this order.
public enum ItemCategory {

    // Food and drink for the ship's meals. Only these have a storage place (Item.place).
    PROVISIONS("Provisions"),
    // Things crew buy for themselves, such as cigarettes, soft drinks, toiletries.
    PERSONAL_ITEMS("Personal items");

    // The value in an item file (the import template). The file format, so never translated.
    private final String fileValue;

    ItemCategory(String fileValue) {
        this.fileValue = fileValue;
    }

    public String fileValue() {
        return fileValue;
    }

    public String labelKey() {
        return "item.category." + name();
    }

    /** The category a file value names ("Provisions", "personal items"; case and spacing ignored), or null. */
    public static ItemCategory fromFileValue(String value) {
        String normalized = ImportNames.normalize(value);
        return Arrays.stream(values())
                .filter(category -> ImportNames.normalize(category.fileValue).equals(normalized))
                .findFirst().orElse(null);
    }

    /** The category with this enum name, or null for null, blank or an unknown name. */
    public static ItemCategory fromName(String name) {
        return Arrays.stream(values()).filter(category -> category.name().equals(name)).findFirst().orElse(null);
    }
}
