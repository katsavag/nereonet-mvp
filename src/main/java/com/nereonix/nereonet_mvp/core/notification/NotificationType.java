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
    /** An announcement to the ship. None is sent yet: the crew's announcements come with P27. */
    ANNOUNCEMENT("announcements");

    private final String icon;

    NotificationType(String icon) {
        this.icon = icon;
    }

    /** The icon it is shown with: the sidebar entry's icon of the same subject, layout/parts :: navIcon(icon). */
    public String getIcon() {
        return icon;
    }
}
