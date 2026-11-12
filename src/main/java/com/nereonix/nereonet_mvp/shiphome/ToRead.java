package com.nereonix.nereonet_mvp.shiphome;

import java.time.Instant;

import com.nereonix.nereonet_mvp.core.library.LibraryItem;
import com.nereonix.nereonet_mvp.core.library.LibraryItemVersion;

/**
 * One card of "To read" on the crew home page (P26): something the user is asked to read and has not acknowledged in
 * its current version. kind: its type (label home.toRead.kind.<KIND>); date: when that version was published; url:
 * where the card opens it; version: the library version, for the type icon.
 * P27 adds announcements as a second kind.
 */
public record ToRead(Kind kind, String title, Instant date, String url, LibraryItemVersion version) {

    public enum Kind {
        LIBRARY
    }

    static ToRead of(LibraryItem item) {
        LibraryItemVersion version = item.getCurrentVersion();
        return new ToRead(Kind.LIBRARY, item.getTitle(), version.getPublishedAt(), "/ship/library/" + item.getId(),
                version);
    }
}
