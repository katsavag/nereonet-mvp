package com.nereonix.nereonet_mvp.shiphome;

import java.time.Instant;

import com.nereonix.nereonet_mvp.core.announcement.AnnouncementRecipient;
import com.nereonix.nereonet_mvp.core.library.LibraryItem;
import com.nereonix.nereonet_mvp.core.library.LibraryItemVersion;

/**
 * One card of "To read" on the crew home page (P26): something the user is asked to read and has not acknowledged
 * (a library item in its current version). kind: its type (labels home.toRead.kind.<KIND> and home.toRead.date.<KIND>);
 * date: when that version was published, or the announcement sent; url: where the card opens it; version: the library
 * version, for the type icon, null for an announcement (P27), which shows the announcements icon.
 */
public record ToRead(Kind kind, String title, Instant date, String url, LibraryItemVersion version) {

    public enum Kind {
        LIBRARY,
        ANNOUNCEMENT
    }

    static ToRead of(LibraryItem item) {
        LibraryItemVersion version = item.getCurrentVersion();
        return new ToRead(Kind.LIBRARY, item.getTitle(), version.getPublishedAt(), "/ship/library/" + item.getId(),
                version);
    }

    static ToRead of(AnnouncementRecipient recipient) {
        var announcement = recipient.getAnnouncement();
        return new ToRead(Kind.ANNOUNCEMENT, announcement.getTitle(), announcement.getCreatedAt(),
                "/ship/news/" + announcement.getId(), null);
    }
}
