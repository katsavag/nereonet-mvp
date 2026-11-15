package com.nereonix.nereonet_mvp.core.form;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;

import com.nereonix.nereonet_mvp.core.item.Item;

/**
 * The one rule for every quantity a person types, used as {@code @quantityRules} in templates.
 *
 * The rule depends on the item's unit (free text): it is normalised (case, a final dot, spaces) and mapped to a
 * {@link Kind}: kg and lt take up to 3 decimals; g, ml and every countable or unknown unit (pcs, box, can, ...) whole
 * numbers only. Each kind has a maximum for one line (app.quantity.max.*); a store count may hold app.quantity.store-
 * factor times that (the whole store). An item's own "Maximum per request" replaces the unit maximum in requests,
 * orders and receipts. The minimum is above 0, except where 0 means "none" ({@link Use#RECEIPT}, {@link Use#COUNT}).
 *
 * Text is read as typed: "2,5" is 2.5. A value that is not changed (the quantity already on a request, an order line or
 * the quantity ordered on a receipt) is never refused for being over the maximum: the limit applies to what is
 * entered or changed. The page shows the same rule: layout/parts :: quantityInput and quantityHint, js/quantity.js.
 */
@Component("quantityRules")
public class QuantityRules {

    /** At most 9 whole digits and 3 decimals: the quantity columns of the database. */
    public static final int MAX_INTEGER_DIGITS = 9;
    public static final int MAX_DECIMALS = 3;

    /** What a unit is measured in, and how many decimals it takes. */
    public enum Kind {
        KG(3), LT(3), G(0), ML(0), COUNTABLE(0);

        private final int decimals;

        Kind(int decimals) {
            this.decimals = decimals;
        }

        public int decimals() {
            return decimals;
        }
    }

    /** Where a quantity is typed: whether 0 is allowed, which maximum, and the wording of "too much". */
    public enum Use {
        /** New request and the Master's vessel order (crew screens): above 0, the line maximum. */
        REQUEST(false, true, false),
        /** The office's order line and Add an item: above 0, the line maximum. */
        OFFICE_ORDER(false, true, false),
        /** Confirm receipt: 0 is "Not delivered"; the line maximum. */
        RECEIPT(true, true, false),
        /** The store count, by hand or by voice: 0 is "none left"; the store maximum. */
        COUNT(true, false, true),
        /** The supplier's quote (office): above 0, the line maximum of the unit. */
        QUOTE(false, false, false);

        private final boolean zeroAllowed;
        private final boolean itemMaximum;
        private final boolean store;

        Use(boolean zeroAllowed, boolean itemMaximum, boolean store) {
            this.zeroAllowed = zeroAllowed;
            this.itemMaximum = itemMaximum;
            this.store = store;
        }

        String maxKey() {
            return "quantity.error.max." + name().toLowerCase(Locale.ROOT).replace("_", "");
        }
    }

    /** The outcome of reading one quantity: the value, or the error's message code and arguments. */
    public record Check(BigDecimal value, String code, Object[] args) implements Serializable {

        public boolean ok() {
            return code == null;
        }

        /** The error as a message, for a flash attribute or a JSON answer. */
        public MessageSourceResolvable message() {
            return code == null ? null : new DefaultMessageSourceResolvable(new String[] {code}, args);
        }

        /** Records the error on the field (nothing when ok). */
        public void reject(Errors errors, String field) {
            if (code != null) {
                errors.rejectValue(field, code, args, null);
            }
        }
    }

    /**
     * A refused quantity of a page whose lines each post on their own (the vessel order, the office order, the store
     * count): a flash attribute "quantityError", so the line shows what was typed, marked invalid, with the message
     * under it. id: the line's (or item's) id; value: as typed; message: the error.
     */
    public record LineError(Long id, String value, MessageSourceResolvable message) implements Serializable {

        public static LineError of(Long id, String value, Check check) {
            return new LineError(id, value, check.message());
        }

        /** True when this error belongs to the line with this id (templates). */
        public boolean isFor(Long lineId) {
            return id != null && id.equals(lineId);
        }
    }

    /** The rule of one quantity field. unit: the item's unit as written ("Kgs"), null when it has none. */
    public record Rule(String unit, Kind kind, Use use, BigDecimal max) {

        public int decimals() {
            return kind.decimals();
        }

        public boolean wholeNumbers() {
            return kind.decimals() == 0;
        }

        public boolean zeroAllowed() {
            return use.zeroAllowed;
        }

        /** "2000": the maximum as the hint and the input's data-quantity-max show it. */
        public String maxText() {
            return max.stripTrailingZeros().toPlainString();
        }

        /** "numeric" (whole numbers: phones show digits only) or "decimal". */
        public String inputMode() {
            return wholeNumbers() ? "numeric" : "decimal";
        }

        /** The input's step: 1, or 0.001. */
        public String step() {
            return BigDecimal.ONE.movePointLeft(decimals()).toPlainString();
        }

        /** The unit for the messages: as written, or "units" when there is none. */
        public Object unitLabel() {
            return unit != null ? unit : new DefaultMessageSourceResolvable("quantity.unit.none");
        }

        /** The hint under the field: "Up to 2000 kg" or "Whole numbers, up to 5000 pcs". */
        public String hintKey() {
            return wholeNumbers() ? "quantity.hint.whole" : "quantity.hint.max";
        }

        public Object[] hintArgs() {
            return new Object[] {maxText(), unitLabel()};
        }

        /** Reads a quantity typed in this field. A blank one is "Enter a quantity". */
        public Check check(String text) {
            return check(text, null);
        }

        /** As check(text); unchanged: the value already saved, which may stay over the maximum. */
        public Check check(String text, BigDecimal unchanged) {
            if (text == null || text.isBlank()) {
                return error("quantity.error.required");
            }
            String typed = text.strip().replace(',', '.');
            if (!NUMBER.matcher(typed).matches()) {
                return error("quantity.error.number");
            }
            return check(new BigDecimal(typed), unchanged);
        }

        /** The same checks for a number already read (the quote form binds numbers). */
        public Check check(BigDecimal value, BigDecimal unchanged) {
            if (value == null) {
                return error("quantity.error.required");
            }
            if (value.signum() < 0 || (value.signum() == 0 && !zeroAllowed())) {
                return error(zeroAllowed() ? "quantity.error.zeroOrMore" : "quantity.error.aboveZero");
            }
            BigDecimal stripped = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
            if (stripped.scale() > decimals()) {
                return wholeNumbers() ? error("quantity.error.whole", unitLabel())
                        : error("quantity.error.decimals", decimals(), unitLabel());
            }
            boolean kept = unchanged != null && unchanged.compareTo(value) == 0;
            if (!kept && value.compareTo(max) > 0) {
                return error(use.maxKey(), maxText(), unitLabel());
            }
            if (stripped.precision() - stripped.scale() > MAX_INTEGER_DIGITS) {
                return error(use.maxKey(), maxText(), unitLabel());
            }
            return new Check(stripped.scale() < 0 ? stripped.setScale(0) : stripped, null, null);
        }

        private static Check error(String code, Object... args) {
            return new Check(null, code, args);
        }
    }

    // A number as typed after the comma became a dot: "12", "2.5", ".5", "5.", "-1" (refused as below the minimum).
    private static final Pattern NUMBER = Pattern.compile("-?(\\d+(\\.\\d*)?|\\.\\d+)");

    // The units each kind knows, normalised; any other unit is countable.
    private static final Map<String, Kind> UNITS = new LinkedHashMap<>();

    static {
        for (String unit : new String[] {"kg", "kgs", "kgr", "kilo", "kilos", "kilogram", "kilograms", "κιλό", "κιλά"}) {
            UNITS.put(unit, Kind.KG);
        }
        for (String unit : new String[] {"lt", "l", "ltr", "ltrs", "lts", "litre", "litres", "liter", "liters",
                "λίτρο", "λίτρα"}) {
            UNITS.put(unit, Kind.LT);
        }
        for (String unit : new String[] {"g", "gr", "grs", "gram", "grams", "gramme", "grammes"}) {
            UNITS.put(unit, Kind.G);
        }
        for (String unit : new String[] {"ml", "mls", "millilitre", "millilitres", "milliliter", "milliliters"}) {
            UNITS.put(unit, Kind.ML);
        }
    }

    private final Map<Kind, BigDecimal> lineMax;
    private final BigDecimal storeFactor;

    public QuantityRules(@Value("${app.quantity.max.kg:2000}") BigDecimal kg,
                         @Value("${app.quantity.max.lt:2000}") BigDecimal lt,
                         @Value("${app.quantity.max.g:50000}") BigDecimal g,
                         @Value("${app.quantity.max.ml:50000}") BigDecimal ml,
                         @Value("${app.quantity.max.countable:5000}") BigDecimal countable,
                         @Value("${app.quantity.store-factor:10}") BigDecimal storeFactor) {
        this.lineMax = Map.of(Kind.KG, kg, Kind.LT, lt, Kind.G, g, Kind.ML, ml, Kind.COUNTABLE, countable);
        this.storeFactor = storeFactor;
    }

    /** The unit as compared: no spaces around or inside, lower case, no final dot; "" for none. */
    public static String normalize(String unit) {
        if (unit == null) {
            return "";
        }
        String u = unit.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        while (u.endsWith(".")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /** The kind of a unit: kg, lt, g, ml, or countable for every other unit (pcs, box, can, ...) and none. */
    public static Kind kindOf(String unit) {
        return UNITS.getOrDefault(normalize(unit), Kind.COUNTABLE);
    }

    /** The rule of a quantity of this item; its own "Maximum per request" applies where the use allows it. */
    public Rule forItem(Item item, Use use) {
        return rule(item.getUnit(), use.itemMaximum ? item.getMaxPerRequest() : null, use);
    }

    /** The rule for a unit (the quote's pack unit, a new item's unit); null or blank: countable. */
    public Rule forUnit(String unit, Use use) {
        return rule(unit, null, use);
    }

    /** Templates: forItem(item, 'REQUEST'). */
    public Rule forItem(Item item, String use) {
        return forItem(item, Use.valueOf(use));
    }

    /** Templates: forUnit(unit, 'REQUEST'). */
    public Rule forUnit(String unit, String use) {
        return forUnit(unit, Use.valueOf(use));
    }

    /** The value of a quantity field (templates): text as typed, or a number written plain (10, never 1E+1). */
    public String text(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof BigDecimal number ? number.toPlainString() : value.toString();
    }

    /** The line maximum of a kind (without an item's own maximum). */
    public BigDecimal lineMax(Kind kind) {
        return lineMax.get(kind);
    }

    /**
     * For js/quantity.js, where the unit is typed on the page (a new item of a request): every unit it knows, by
     * name, with its decimals and maximum for this use, as JSON: {"kg":[3,"2000"],...}; any other unit is countable.
     */
    public String unitsJson(String use) {
        Use u = Use.valueOf(use);
        StringBuilder json = new StringBuilder("{");
        UNITS.forEach((unit, kind) -> json.append('"').append(unit).append("\":[").append(kind.decimals())
                .append(",\"").append(rule(unit, null, u).maxText()).append("\"],"));
        Rule countable = rule(null, null, u);
        json.append("\"\":[0,\"").append(countable.maxText()).append("\"]}");
        return json.toString();
    }

    private Rule rule(String unit, BigDecimal itemMax, Use use) {
        Kind kind = kindOf(unit);
        BigDecimal max = itemMax != null ? itemMax : lineMax.get(kind);
        if (use.store) {
            max = max.multiply(storeFactor);
        }
        String shown = unit == null || unit.isBlank() ? null : unit.strip();
        return new Rule(shown, kind, use, max);
    }
}
