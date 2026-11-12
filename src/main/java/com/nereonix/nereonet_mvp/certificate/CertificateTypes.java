package com.nereonix.nereonet_mvp.certificate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.nereonix.nereonet_mvp.core.certificate.CertificateRepository.TypeCount;

/**
 * Certificate types (P20). The type stays free text; the form suggests (datalist) the types already used in this
 * installation, most used first, then the starter list below. On save a type that differs from a known one only in
 * case or surrounding spaces is stored as that one, so "stcw basic safety training " never starts a second type.
 * Two types are the same when they are equal after strip() and toLowerCase(Locale.ROOT).
 */
public final class CertificateTypes {

    /** Offered before anything has been entered; type names are data and stay English in both languages. */
    public static final List<String> STARTER = List.of(
            "STCW Basic Safety Training",
            "Certificate of Competency",
            "Flag State Endorsement",
            "GMDSS Operator",
            "Advanced Fire Fighting",
            "Proficiency in Survival Craft",
            "Medical First Aid",
            "Security Awareness",
            "Ship Security Officer",
            "Tanker Endorsement",
            "Medical Fitness Certificate",
            "Seaman's Book",
            "Passport",
            "Yellow Fever Vaccination",
            "US Visa C1/D");

    private CertificateTypes() {
    }

    /** What two spellings of the same type have in common. */
    public static String sameTypeKey(String type) {
        return type.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * The suggestions: the types in use (counts from the database, one row per spelling), most used first, then by
     * name; then the starter types not in use, in their order. Spellings of one type are counted together and shown
     * as the most used one.
     */
    public static List<String> suggestions(List<TypeCount> used) {
        Map<String, Known> known = merge(used);
        List<Known> byUse = new ArrayList<>(known.values());
        byUse.sort((a, b) -> a.count != b.count ? Long.compare(b.count, a.count)
                : a.spelling.compareToIgnoreCase(b.spelling));
        List<String> suggestions = new ArrayList<>(byUse.stream().map(k -> k.spelling).toList());
        STARTER.stream().filter(type -> !known.containsKey(sameTypeKey(type))).forEach(suggestions::add);
        return suggestions;
    }

    /**
     * The type as it is stored: the most used spelling of the same type in use, else the starter list's spelling,
     * else the type as entered, stripped.
     */
    public static String canonical(String type, List<TypeCount> used) {
        String key = sameTypeKey(type);
        Known inUse = merge(used).get(key);
        if (inUse != null) {
            return inUse.spelling;
        }
        Optional<String> starter = STARTER.stream().filter(s -> sameTypeKey(s).equals(key)).findFirst();
        return starter.orElse(type.strip());
    }

    private static final class Known {
        String spelling;
        long spellingCount;
        long count;
    }

    // Spellings of one type together: their total, and the most used spelling (the first by name on a tie).
    private static Map<String, Known> merge(List<TypeCount> used) {
        Map<String, Known> known = new LinkedHashMap<>();
        for (TypeCount row : used) {
            Known k = known.computeIfAbsent(sameTypeKey(row.type()), key -> new Known());
            k.count += row.count();
            if (k.spelling == null || row.count() > k.spellingCount
                    || (row.count() == k.spellingCount && row.type().strip().compareTo(k.spelling) < 0)) {
                k.spelling = row.type().strip();
                k.spellingCount = row.count();
            }
        }
        return known;
    }
}
