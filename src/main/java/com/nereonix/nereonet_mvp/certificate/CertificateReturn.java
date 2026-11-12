package com.nereonix.nereonet_mvp.certificate;

import java.util.regex.Pattern;

/**
 * Where the add and edit certificate forms go back to (P20): the page they were opened from, sent along as
 * ?returnTo= on the link and as a hidden field on the form. Only office pages that open these forms are accepted,
 * each with a numeric id and a query string of URL-safe characters: the Certificates list with its filters, a ship's
 * certificate matrix or list, a ship's Overview tab, the Overview, a crew member's page. Anything else (another
 * site, "//host", a line break) is ignored and the form goes back to its usual page.
 */
public final class CertificateReturn {

    private static final Pattern ALLOWED = Pattern.compile(
            "/office(/certificates|/ships/\\d{1,18}(/certificates(/list)?)?|/crew/\\d{1,18}/edit)?"
                    + "(\\?[A-Za-z0-9=&%+,._~-]{0,1000})?");

    private CertificateReturn() {
    }

    /** returnTo when it is one of the pages above, else null. */
    public static String clean(String returnTo) {
        return returnTo != null && ALLOWED.matcher(returnTo).matches() ? returnTo : null;
    }

    /** returnTo when it is one of the pages above, else fallback. */
    public static String or(String returnTo, String fallback) {
        String clean = clean(returnTo);
        return clean == null ? fallback : clean;
    }
}
