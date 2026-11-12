package com.nereonix.nereonet_mvp.core.notification;

/**
 * What a notification is about (P23), shown as its icon on /notifications. Notifications saved before P23 have none
 * and show the bell.
 */
public enum NotificationType {
    /** A certificate that expires soon (the daily reminder). */
    CERTIFICATE("certificates"),
    /** A library document to read. */
    LIBRARY("library"),
    /** An announcement from the office, sent to each recipient when it is sent (P27). */
    ANNOUNCEMENT("announcements"),
    /** An item request waiting for the Master (P33), sent when it is submitted. */
    REQUEST("approvals"),
    /** A ship order sent to the office (P35), to every enabled office user; its confirmation by the office (P36), to
     *  the ship's Master and Chief cook. */
    ORDER("orders");

    private final String icon;

    NotificationType(String icon) {
        this.icon = icon;
    }

    /** The icon it is shown with: the sidebar entry's icon of the same subject, layout/parts :: navIcon(icon). */
    public String getIcon() {
        return icon;
    }
}
