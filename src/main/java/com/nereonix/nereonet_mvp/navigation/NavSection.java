package com.nereonix.nereonet_mvp.navigation;

import java.util.List;

/**
 * The sidebar entry a URL belongs to. A page below an entry keeps it active: /office/crew/12/edit is CREW,
 * /office/ships/3/library is SHIPS. key() is what the layout compares (navSection == 'crew'); the ship user's phone
 * tab of a section depends on their role (ShipNav.tabOf).
 */
public enum NavSection {

    // Office, Operations
    OVERVIEW("overview", List.of()),           // /office itself only, see of()
    SHIPS("ships", List.of("/office/ships")),
    // The office's Crew and, for the crew, My crew: the same entry key and icon.
    CREW("crew", List.of("/office/crew", "/ship/crew")),
    CERTIFICATES("certificates", List.of("/office/certificates")),
    // How charterers vet a vessel: RightShip, SIRE 2.0.
    VETTING("vetting", List.of("/office/vetting")),
    // The office list and, for the crew, their own announcements, called News since P39 (/ship/news; the old
    // /ship/announcements URLs redirect there): the same entry key and icon.
    ANNOUNCEMENTS("announcements", List.of("/office/announcements", "/ship/news", "/ship/announcements")),
    // The orders the ships have sent.
    ORDERS("orders", List.of("/office/orders")),
    // The suppliers the office asks for quotes. An RFQ's pages are below its order, so under Orders.
    SUPPLIERS("suppliers", List.of("/office/suppliers")),
    // Office, Administration. Office invitations are part of the Office users page.
    USERS("users", List.of("/office/users", "/office/invitations")),
    // Which documents each rank needs, per vessel type.
    REQUIREMENTS("requirements", List.of("/office/requirements")),
    AUDIT("audit", List.of("/office/audit")),
    // Ship. Home is /ship itself only, see of().
    HOME("home", List.of()),
    LIBRARY("library", List.of("/ship/library")),
    // The Master's approvals. On phones a tab of the Master's since P65 (ShipNav).
    APPROVALS("approvals", List.of("/ship/approvals")),
    // The Master's ship order and past orders. On phones a tab of the Master's since P65.
    ORDER("order", List.of("/ship/order", "/ship/orders")),
    // The Chief cook's store. On phones a tab of the Chief cook's since P65.
    STORES("stores", List.of("/ship/stores")),
    // The Chief cook's and crew's own requests to the Master. On phones a tab of the Chief cook's since P65,
    // under More for crew members.
    REQUESTS("requests", List.of("/ship/requests")),
    // Ship, Account: My profile. More (/ship/more) is the phone tab bar's list of what is not a tab and
    // Sign out; it has no sidebar entry.
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
