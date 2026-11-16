package com.nereonix.nereonet_mvp.core.form;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * The units a person can choose for an item, in the order of the dropdown (layout/parts :: unitSelect, as
 * {@code @itemUnits}). They are stored and shown as written here, never translated. The item import and the
 * supplier's pack unit take any unit; {@link QuantityRules} maps each unit to its rule.
 */
@Component("itemUnits")
public class ItemUnits {

    public static final List<String> ALL =
            List.of("kg", "g", "lt", "ml", "pcs", "box", "bottle", "can", "bag", "pack", "dozen");

    /** True for a unit of the list, exactly as written there. */
    public static boolean listed(String unit) {
        return unit != null && ALL.contains(unit);
    }

    /** The options of the dropdown: the list, then the unit an item already has when it is not on the list. */
    public List<String> options(String current) {
        if (current == null || current.isBlank() || listed(current)) {
            return ALL;
        }
        List<String> options = new ArrayList<>(ALL);
        options.add(current);
        return options;
    }
}
