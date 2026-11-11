package com.nereonix.nereonet_mvp.dashboard;

import java.util.Locale;

/**
 * The kinds of open item on the Overview's "Needs attention" card (P09), most urgent first: the list is sorted in
 * this order, then by date within a kind (oldest or soonest first).
 */
public enum AttentionType {

    /** A certificate past its expiry date. */
    CERTIFICATE_EXPIRED(Group.CERTIFICATES),
    /** A certificate expiring within DashboardService.OPEN_ITEM_CERTIFICATE_DAYS. */
    CERTIFICATE_EXPIRING(Group.CERTIFICATES),
    /** An approved item request never exported. */
    REQUEST_NOT_EXPORTED(Group.REQUESTS),
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
        CERTIFICATES, REQUESTS, INVITATIONS, ACKNOWLEDGEMENTS;

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
