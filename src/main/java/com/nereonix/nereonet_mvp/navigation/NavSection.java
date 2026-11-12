package com.nereonix.nereonet_mvp.navigation;

import java.util.List;

/**
 * The sidebar entry a URL belongs to. A page below an entry keeps it active: /office/crew/12/edit is CREW,
 * /office/ships/3/library is SHIPS. key() is what the layout compares (navSection == 'crew').
 */
public enum NavSection {

    // Office, Operations
    OVERVIEW("overview", List.of()),           // /office itself only, see of()
    SHIPS("ships", List.of("/office/ships")),
    CREW("crew", List.of("/office/crew")),
    CERTIFICATES("certificates", List.of("/office/certificates")),
    // The office list and, for the crew, their own announcements (P27): the same entry key and icon.
    ANNOUNCEMENTS("announcements", List.of("/office/announcements", "/ship/announcements")),
    REQUESTS("requests", List.of("/office/requests")),
    // Office, Administration. Office invitations are part of the Office users page.
    USERS("users", List.of("/office/users", "/office/invitations")),
    AUDIT("audit", List.of("/office/audit")),
    // Ship. Home is /ship itself only (P26), see of().
    HOME("home", List.of()),
    LIBRARY("library", List.of("/ship/library")),
    // Account, everyone
    NOTIFICATIONS("notifications", List.of("/notifications"));

    private final String key;
    private final List<String> prefixes;

    NavSection(String key, List<String> prefixes) {
        this.key = key;
        this.prefixes = prefixes;
    }

    public String key() {
        return key;
    }

    /**
     * The section of a path within the application (no context path), or null for a page with no sidebar entry
     * (the sign-in page, /error).
     */
    public static NavSection of(String path) {
        if (path == null) {
            return null;
        }
        String p = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (p.equals("/office")) {
            return OVERVIEW;
        }
        if (p.equals("/ship")) {
            return HOME;
        }
        for (NavSection section : values()) {
            for (String prefix : section.prefixes) {
                if (p.equals(prefix) || p.startsWith(prefix + "/")) {
                    return section;
                }
            }
        }
        return null;
    }
}
