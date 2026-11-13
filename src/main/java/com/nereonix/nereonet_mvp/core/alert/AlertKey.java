package com.nereonix.nereonet_mvp.core.alert;

import java.util.ArrayList;
import java.util.List;

/**
 * What identifies one alert (P43): its kind and the id of its subject (AlertKind says which record that is). Written
 * as "CERTIFICATE_EXPIRING:12" in the snooze form's items and in the lists' checkboxes.
 */
public record AlertKey(AlertKind kind, long subjectId) {

    public String param() {
        return kind.name() + ":" + subjectId;
    }

    /** The key written by param(), or null for anything else (so an odd value is ignored, never an error). */
    public static AlertKey parse(String value) {
        if (value == null) {
            return null;
        }
        int colon = value.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        try {
            return new AlertKey(AlertKind.valueOf(value.substring(0, colon).strip()),
                    Long.parseLong(value.substring(colon + 1).strip()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Every key of the values given (a checkbox may carry several, joined with a comma); unknown ones left out. */
    public static List<AlertKey> parseAll(List<String> values) {
        List<AlertKey> keys = new ArrayList<>();
        if (values == null) {
            return keys;
        }
        for (String value : values) {
            for (String part : value.split(",")) {
                AlertKey key = parse(part);
                if (key != null && !keys.contains(key)) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }
}
