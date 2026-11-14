package com.nereonix.nereonet_mvp.dashboard;

import java.util.Locale;

/**
 * The kinds of open item on the Overview's "Needs attention" card (P09), most urgent first: the list is sorted in
 * this order, then by date within a kind (oldest or soonest first).
 */
public enum AttentionType {

    /** A certificate past its expiry date. */
    CERTIFICATE_EXPIRED(Group.CERTIFICATES),
    /** A seafarer on board missing a required document, an expired one included (P42): one item per person. */
    DOCUMENTS_MISSING(Group.DOCUMENTS),
    /** A vessel's SIRE 2.0 operator comments due within 3 days, or overdue (P41): a deadline that cannot be missed. */
    SIRE_COMMENTS(Group.VETTING),
    /** A vessel's RightShip inspection expired, unacceptable or expiring within 60 days (P40). */
    VETTING_INSPECTION(Group.VETTING),
    /** A vessel's latest SIRE 2.0 report no longer current, or current for 60 days at most (P41). */
    SIRE_REPORT(Group.VETTING),
    /** An open SIRE 2.0 observation past its target date, on a vessel's latest inspection (P41). */
    SIRE_OBSERVATION(Group.VETTING),
    /** A vessel's RightShip Safety Score of 2 or below (P40). */
    VETTING_SCORE(Group.VETTING),
    /** A certificate expiring within DashboardService.OPEN_ITEM_CERTIFICATE_DAYS. */
    CERTIFICATE_EXPIRING(Group.CERTIFICATES),
    /** An order a ship has sent (P36) that the office has never exported. */
    ORDER_NOT_EXPORTED(Group.ORDERS),
    /** An open request for quotes (P44) with quotes from at least two suppliers: ready to compare and award. */
    RFQ_READY(Group.QUOTES),
    /** An open request for quotes past its reply-by date with fewer than two quotes (P44). */
    RFQ_PAST_REPLY_BY(Group.QUOTES),
    /** A crew invitation that expired, or was sent more than 48 hours ago and is still not accepted. */
    INVITATION(Group.INVITATIONS),
    /** An announcement sent more than 7 days ago that not every recipient has acknowledged. */
    ANNOUNCEMENT(Group.ACKNOWLEDGEMENTS),
    /** A Required library item published more than 7 days ago that not everyone on board has acknowledged. */
    LIBRARY_ITEM(Group.ACKNOWLEDGEMENTS);

    private final Group group;

    AttentionType(Group group) {
        this.group = group;
    }

    public Group group() {
        return group;
    }

    /**
     * The kinds as the Overview filters them (/office?type=invitations) and as the fleet table counts them. Each
     * links to its full list: see the "full lists" links in dashboard/dashboard.html.
     */
    public enum Group {
        CERTIFICATES, DOCUMENTS, VETTING, ORDERS, QUOTES, INVITATIONS, ACKNOWLEDGEMENTS;

        /** The full list of this kind outside the Overview (P43: the By category card), or null for the Overview
         *  filtered to it (/office?type=...). */
        public String listUrl() {
            return switch (this) {
                case CERTIFICATES -> "/office/certificates?status=expired,30&activeShips=true";
                case DOCUMENTS -> "/office/crew?status=active&documents=missing";
                case VETTING -> "/office/vetting?status=attention";
                case ORDERS -> "/office/orders?exported=no";
                case QUOTES, INVITATIONS, ACKNOWLEDGEMENTS -> null;
            };
        }

        /** The sidebar icon of the By category tile (layout/parts :: navIcon). */
        public String icon() {
            return switch (this) {
                case CERTIFICATES, DOCUMENTS -> "certificates";
                case VETTING -> "vetting";
                case ORDERS -> "orders";
                case QUOTES -> "suppliers";
                case INVITATIONS -> "crew";
                case ACKNOWLEDGEMENTS -> "announcements";
            };
        }

        /** The tone of the tile's status badge while it has open items: danger, warning or info. */
        public String tone() {
            return switch (this) {
                case CERTIFICATES, DOCUMENTS, VETTING -> "danger";
                case ORDERS -> "warning";
                case QUOTES, INVITATIONS, ACKNOWLEDGEMENTS -> "info";
            };
        }

        /** The value of the Overview's "type" parameter. */
        public String param() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The group named by the "type" parameter, or null when it is missing or unknown (then nothing is filtered). */
        public static Group fromParam(String value) {
            for (Group group : values()) {
                if (group.param().equals(value)) {
                    return group;
                }
            }
            return null;
        }
    }
}
