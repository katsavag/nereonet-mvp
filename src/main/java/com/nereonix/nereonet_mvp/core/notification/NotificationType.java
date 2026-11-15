package com.nereonix.nereonet_mvp.core.notification;

/**
 * What a notification is about, shown as its icon on /notifications. Notifications saved before P23 have none
 * and show the bell.
 */
public enum NotificationType {
    /** A certificate that expires soon (the daily reminder). */
    CERTIFICATE("certificates"),
    /** A library document to read. */
    LIBRARY("library"),
    /** An announcement from the office, sent to each recipient when it is sent. */
    ANNOUNCEMENT("announcements"),
    /** An item request waiting for the Master, sent when it is submitted. */
    REQUEST("approvals"),
    /** A ship order sent to the office, to every enabled office user; its confirmation by the office, to
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
