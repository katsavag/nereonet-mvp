package com.nereonix.nereonet_mvp.ship;

import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * The small flag images next to a country (P43): the 4x3 SVG flags of the flag-icons project (MIT licence,
 * static/vendor/flags/LICENSE), one file per ISO 3166 code of ship/Countries, served by the app itself. Used as
 * {@code @flags} by layout/parts :: flag and country. The files that exist are read once at startup, so a country
 * whose file is missing simply gets no image, never a broken one.
 */
@Component("flags")
public class Flags {

    static final String FOLDER = "/vendor/flags/";

    private final Set<String> codes = new HashSet<>();

    public Flags() {
        try {
            for (Resource file : new PathMatchingResourcePatternResolver().getResources("classpath:static" + FOLDER + "*.svg")) {
                String name = file.getFilename();
                if (name != null) {
                    codes.add(name.substring(0, name.length() - ".svg".length()).toUpperCase(Locale.ROOT));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot list the flag images", e);
        }
    }

    /** There is a flag image for this country code ("GR"); false for null, an unknown code or a missing file. */
    public boolean has(String code) {
        return code != null && codes.contains(code.toUpperCase(Locale.ROOT));
    }

    /** The image's path within the app ("/vendor/flags/gr.svg"), for @{...} (which adds the content hash); null
     *  when there is none. */
    public String path(String code) {
        return has(code) ? FOLDER + code.toLowerCase(Locale.ROOT) + ".svg" : null;
    }

    /** How many flag images are available (the tests). */
    public int count() {
        return codes.size();
    }
}
