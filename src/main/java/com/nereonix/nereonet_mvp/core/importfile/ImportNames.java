package com.nereonix.nereonet_mvp.core.importfile;

import java.text.Normalizer;
import java.util.Locale;

// How column names and values are compared when importing a file (P24).
public final class ImportNames {

    private ImportNames() {
    }

    /** "E-mail Address " -> "e mail address", "Ονοματεπώνυμο" -> "ονοματεπωνυμο": lower case, no accents, words only. */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String plain = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    /** "C/E", "c.e." and "CE" -> "ce": only letters and digits, for matching short forms. */
    public static String compact(String value) {
        return normalize(value).replace(" ", "");
    }
}
