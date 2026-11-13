package com.nereonix.nereonet_mvp.navigation;

import java.util.List;

/**
 * The sidebar entry a URL belongs to. A page below an entry keeps it active: /office/crew/12/edit is CREW,
 * /office/ships/3/library is SHIPS. key() is what the layout compares (navSection == 'crew'); tab() is the crew's
 * phone tab bar entry (navTab == 'more'), the same except for the entries under More (P31).
 */
public enum NavSection {

    // Office, Operations
    OVERVIEW("overview", List.of()),           // /office itself only, see of()
    SHIPS("ships", List.of("/office/ships")),
    // The office's Crew and, for the crew, My crew (P31): the same entry key and icon.
    CREW("crew", List.of("/office/crew", "/ship/crew")),
    CERTIFICATES("certificates", List.of("/office/certificates")),
    // The office list and, for the crew, their own announcements (P27), called News since P39 (/ship/news; the old
    // /ship/announcements URLs redirect there): the same entry key and icon.
    ANNOUNCEMENTS("announcements", List.of("/office/announcements", "/ship/news", "/ship/announcements")),
    // The orders the ships have sent (P36).
    ORDERS("orders", List.of("/office/orders")),
    // Office, Administration. Office invitations are part of the Office users page.
    USERS("users", List.of("/office/users", "/office/invitations")),
    AUDIT("audit", List.of("/office/audit")),
    // Ship. Home is /ship itself only (P26), see of().
    HOME("home", List.of()),
    LIBRARY("library", List.of("/ship/library")),
    // The Master's approvals (P33). On phones it is under More, so More is its tab.
    APPROVALS("approvals", List.of("/ship/approvals")),
    // The Master's ship order and past orders (P35). On phones under More, as Approvals.
    ORDER("order", List.of("/ship/order", "/ship/orders")),
    // Ship, Account: My profile (P31). More (/ship/more) is the phone tab bar's list of My profile, My crew and Sign
    // out; it has no sidebar entry.
    PROFILE("profile", List.of("/ship/profile")),
    MORE("more", List.of("/ship/more")),
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

    /** The phone tab (P26) that is active on this section's pages: More for My profile, My crew and More itself
     *  (P31) and for Approvals (P33) and Ship order (P35); every other section is its own tab. The office has no tab
     *  bar. */
    public String tab() {
        return this == PROFILE || this == MORE || this == CREW || this == APPROVALS || this == ORDER ? MORE.key : key;
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
