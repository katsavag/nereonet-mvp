package com.nereonix.nereonet_mvp.core.rfq;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a pack's unit into an item's unit (P44), so a 5 L can and 1 L bottles compare per litre. Units of one kind
 * convert (volume: l, lt, ml, cl; mass: kg, g, t, lb; count: pcs, ea, dozen); a unit not listed converts only to
 * itself (the same text, ignoring case and a final dot). Anything else cannot be compared, and the quote form
 * refuses such a pack unit.
 */
public final class Units {

    public static final int UNIT_MAX_LENGTH = 20;

    private record Unit(String kind, BigDecimal factor) {
    }

    // The factor to the kind's base unit (l, kg, pcs).
    private static final Map<String, Unit> KNOWN = Map.ofEntries(
            Map.entry("l", volume("1")), Map.entry("lt", volume("1")), Map.entry("ltr", volume("1")),
            Map.entry("lts", volume("1")), Map.entry("litre", volume("1")), Map.entry("litres", volume("1")),
            Map.entry("liter", volume("1")), Map.entry("liters", volume("1")),
            Map.entry("cl", volume("0.01")), Map.entry("ml", volume("0.001")),
            Map.entry("kg", mass("1")), Map.entry("kgs", mass("1")), Map.entry("kilo", mass("1")),
            Map.entry("kilos", mass("1")), Map.entry("g", mass("0.001")), Map.entry("gr", mass("0.001")),
            Map.entry("grams", mass("0.001")), Map.entry("t", mass("1000")), Map.entry("ton", mass("1000")),
            Map.entry("tonne", mass("1000")), Map.entry("lb", mass("0.45359237")), Map.entry("lbs", mass("0.45359237")),
            Map.entry("pcs", count("1")), Map.entry("pc", count("1")), Map.entry("piece", count("1")),
            Map.entry("pieces", count("1")), Map.entry("ea", count("1")), Map.entry("each", count("1")),
            Map.entry("unit", count("1")), Map.entry("units", count("1")), Map.entry("dozen", count("12")),
            Map.entry("dz", count("12")));

    private Units() {
    }

    /** How many "to" one "from" is (1 l in ml: 1000; 1 g in kg: 0.001), or null when they do not convert. */
    public static BigDecimal factor(String from, String to) {
        String a = normalize(from);
        String b = normalize(to);
        if (a.isEmpty() || b.isEmpty()) {
            return null;
        }
        if (a.equals(b)) {
            return BigDecimal.ONE;
        }
        Unit ua = KNOWN.get(a);
        Unit ub = KNOWN.get(b);
        if (ua == null || ub == null || !ua.kind().equals(ub.kind())) {
            return null;
        }
        return ua.factor().divide(ub.factor(), 10, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    /** True when a pack in "from" can be compared with an item in "to". */
    public static boolean convertible(String from, String to) {
        return factor(from, to) != null;
    }

    private static String normalize(String unit) {
        if (unit == null) {
            return "";
        }
        String u = unit.strip().toLowerCase(Locale.ROOT);
        return u.endsWith(".") ? u.substring(0, u.length() - 1) : u;
    }

    private static Unit volume(String factor) {
        return new Unit("volume", new BigDecimal(factor));
    }

    private static Unit mass(String factor) {
        return new Unit("mass", new BigDecimal(factor));
    }

    private static Unit count(String factor) {
        return new Unit("count", new BigDecimal(factor));
    }
}
