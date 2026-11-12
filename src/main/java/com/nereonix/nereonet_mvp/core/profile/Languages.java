package com.nereonix.nereonet_mvp.core.profile;

import java.text.Collator;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The languages a seafarer can list on their profile (P31): the ISO 639-1 codes the JDK knows (java.util.Locale), with
 * their English names, sorted by name. Codes the JDK has no name for are left out.
 */
public final class Languages {

    private static final Map<String, String> ENGLISH;

    static {
        Collator collator = Collator.getInstance(Locale.ENGLISH);
        Map<String, String> names = new LinkedHashMap<>();
        Arrays.stream(Locale.getISOLanguages())
                .filter(code -> code.length() == 2)
                .filter(code -> !displayName(code, Locale.ENGLISH).equalsIgnoreCase(code))
                .sorted(Comparator.comparing((String code) -> displayName(code, Locale.ENGLISH), collator))
                .forEach(code -> names.put(code, displayName(code, Locale.ENGLISH)));
        ENGLISH = Collections.unmodifiableMap(names);
    }

    private Languages() {
    }

    /** Code -> English name, sorted by name ("el" -> "Greek"). */
    public static Map<String, String> all() {
        return ENGLISH;
    }

    public static boolean isCode(String code) {
        return code != null && ENGLISH.containsKey(code);
    }

    /** The language's name in the given language ("el" -> "Greek", or "Ελληνικά" for the Greek office screens). */
    public static String name(String code, Locale locale) {
        return isCode(code) ? displayName(code, locale) : code;
    }

    public static List<String> names(List<String> codes, Locale locale) {
        return codes.stream().map(code -> name(code, locale)).toList();
    }

    /** "en,el" -> [en, el]; null or empty -> []. Unknown codes are dropped. */
    public static List<String> parse(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        return Arrays.stream(joined.split(",")).map(String::trim).filter(Languages::isCode).distinct().toList();
    }

    public static String join(List<String> codes) {
        return codes == null ? "" : String.join(",", codes.stream().filter(Objects::nonNull).toList());
    }

    private static String displayName(String code, Locale locale) {
        String name = Locale.of(code).getDisplayLanguage(locale);
        // The JDK's names start in lower case in some languages ("ελληνικά"); a value on its own starts with a capital.
        return name.isEmpty() ? code : name.substring(0, 1).toUpperCase(locale) + name.substring(1);
    }
}
