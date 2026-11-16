package com.nereonix.nereonet_mvp.shiporder;

import java.math.BigDecimal;

import com.nereonix.nereonet_mvp.core.form.QuantityRules;
import com.nereonix.nereonet_mvp.core.form.QuantityRules.Check;
import com.nereonix.nereonet_mvp.core.form.QuantityRules.Rule;
import com.nereonix.nereonet_mvp.core.form.QuantityRules.Use;
import com.nereonix.nereonet_mvp.core.order.CountSheet;
import com.nereonix.nereonet_mvp.core.order.OrderReceiptService;

/**
 * A quantity typed in one row of the delivery count: the cook's count and the Master's change. The receipt's
 * rule (0 allowed): the item's, or for a row in the invoice's unit that unit's ("bags": whole numbers). The value is
 * returned in the item's unit (12 bags of 10 kg: 120).
 */
final class CountQuantities {

    private CountQuantities() {
    }

    /** The rule of a row's input. */
    static Rule rule(QuantityRules rules, CountSheet.Row row) {
        return row.isPacked() ? rules.forUnit(row.getUnit(), Use.RECEIPT) : rules.forItem(row.getItem(), Use.RECEIPT);
    }

    /**
     * What was typed (never blank: the callers decide what an empty input means), in the item's unit. The reference
     * itself is always accepted, even above the maximum.
     */
    static Check read(QuantityRules rules, CountSheet.Row row, String typed) {
        Rule rule = rule(rules, row);
        Check check = rule.check(typed, row.getReference());
        if (!check.ok()) {
            return check;
        }
        BigDecimal value = row.toItem(check.value());
        if (!OrderReceiptService.isValidReceivedQuantity(value)) {
            return new Check(null, "quantity.error.max.receipt", new Object[] {rule.maxText(), rule.unitLabel()});
        }
        return new Check(value, null, null);
    }
}
