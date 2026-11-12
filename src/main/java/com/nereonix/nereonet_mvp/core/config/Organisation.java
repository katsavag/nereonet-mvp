package com.nereonix.nereonet_mvp.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The shipping company this installation serves (P30), used as {@code @organisation} in Thymeleaf: its name and a
 * short code, shown at the bottom of the sign-in panel (layout/parts :: authFrame) so people can see they are signing
 * in to the right company. Both come from app.organisation.name / app.organisation.code and are optional: blank, they
 * are not shown (the code alone is never shown).
 */
@Component("organisation")
public class Organisation {

    private final String name;
    private final String code;

    public Organisation(@Value("${app.organisation.name:}") String name,
                        @Value("${app.organisation.code:}") String code) {
        this.name = blankToNull(name);
        this.code = blankToNull(code);
    }

    /** The company's name ("Nereonix Bulk Carriers"), or null when not set. */
    public String name() {
        return name;
    }

    /** The company's short code ("NEREONIX"), or null when not set. */
    public String code() {
        return code;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
