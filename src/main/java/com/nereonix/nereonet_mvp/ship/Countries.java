package com.nereonix.nereonet_mvp.ship;

import java.text.Collator;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// ISO 3166 country codes for the flag field, shown as "Greece (GR)". Names come from the JDK.
// Public for labels(): the flag filter of the announcement form's ship list (P28); and for isCode() and nameOf(): a crew
// member's nationality (P31).
public final class Countries {

    private static final Set<String> CODES = Set.of(Locale.getISOCountries());
    private static final Pattern CODE_AT_END = Pattern.compile("\\(([A-Za-z]{2})\\)$");

    private Countries() {
    }

    // Code -> "Greece (GR)", sorted by country name in the given language.
    public static Map<String, String> labels(Locale locale) {
        Collator collator = Collator.getInstance(locale);
        Map<String, String> labels = new LinkedHashMap<>();
        CODES.stream()
                .sorted(Comparator.comparing((String code) -> name(code, locale), collator))
                .forEach(code -> labels.put(code, name(code, locale) + " (" + code + ")"));
        return labels;
    }

    // "Greece (GR)", "Ελλάδα (GR)" or "gr" -> "GR". Null if blank or not a known country.
    static String toCode(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String value = input.trim();
        Matcher matcher = CODE_AT_END.matcher(value);
        String code = (matcher.find() ? matcher.group(1) : value).toUpperCase(Locale.ROOT);
        return CODES.contains(code) ? code : null;
    }

    // A known ISO 3166 code, exactly as stored ("GR"). A crew member's nationality (P31).
    public static boolean isCode(String code) {
        return code != null && CODES.contains(code);
    }

    // "GR" -> "Greece" in the given language; null for null or an unknown code. A crew member's nationality (P31).
    public static String nameOf(String code, Locale locale) {
        return isCode(code) ? name(code, locale) : null;
    }

    // VesselAPI's country_code (P45): "PA", "pa" or the three-letter "PAN" -> "PA". Null if blank or not a known country.
    public static String codeOf(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String value = code.strip().toUpperCase(Locale.ROOT);
        if (value.length() == 3) {
            for (String candidate : CODES) {
                if (Locale.of("", candidate).getISO3Country().equals(value)) {
                    return candidate;
                }
            }
            return null;
        }
        return CODES.contains(value) ? value : null;
    }

    // VesselAPI's country (P45): an English country name, "Panama" -> "PA", ignoring case. Null if not known.
    public static String codeOfName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String value = name.strip();
        for (String code : CODES) {
            if (name(code, Locale.ENGLISH).equalsIgnoreCase(value)) {
                return code;
            }
        }
        return null;
    }

    // An empty field is fine (flag is optional); anything else must be a known country.
    static boolean isValidOrBlank(String input) {
        return input == null || input.isBlank() || toCode(input) != null;
    }

    private static String name(String code, Locale locale) {
        return Locale.of("", code).getDisplayCountry(locale);
    }
}
