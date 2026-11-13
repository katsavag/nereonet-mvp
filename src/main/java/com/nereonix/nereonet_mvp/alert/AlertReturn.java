package com.nereonix.nereonet_mvp.alert;

/**
 * Where the snooze page and Undo go back to (P43): an office page of this app, as the link or form gave it
 * (/office/certificates?status=expired, /office/vetting, /office?type=vetting); anything else, or nothing, is the
 * Overview. Never a URL from the request that leaves the app.
 */
final class AlertReturn {

    static final String DEFAULT = "/office";

    private AlertReturn() {
    }

    static String clean(String returnTo) {
        if (returnTo == null || returnTo.isBlank()) {
            return DEFAULT;
        }
        String value = returnTo.strip();
        boolean office = value.equals("/office") || value.startsWith("/office/") || value.startsWith("/office?");
        if (!office || value.contains("\\") || value.contains("\r") || value.contains("\n") || value.contains("//")) {
            return DEFAULT;
        }
        return value;
    }

    /** The message key naming the page returned to, for the breadcrumb: Certificates, Vetting or the Overview. */
    static String labelKey(String returnTo) {
        if (returnTo.startsWith("/office/certificates") || returnTo.contains("/certificates")) {
            return "nav.certificates";
        }
        if (returnTo.startsWith("/office/vetting")) {
            return "nav.vetting";
        }
        return "nav.overview";
    }
}
