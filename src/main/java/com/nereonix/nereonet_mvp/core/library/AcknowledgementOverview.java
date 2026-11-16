package com.nereonix.nereonet_mvp.core.library;

import java.time.Instant;

import org.springframework.data.domain.Page;

import com.nereonix.nereonet_mvp.core.list.ListQuery;
import com.nereonix.nereonet_mvp.core.list.ListRequest;
import com.nereonix.nereonet_mvp.core.list.ListSort;
import com.nereonix.nereonet_mvp.core.user.User;

// Who of the crew on board now acknowledged the current version of a Required item: one page of each list, both by
// full name, each with its own pager (readList, notReadList). nextReminderAt: when "Send reminder" may be used again,
// or null when it may be used now.
public record AcknowledgementOverview(LibraryItem item, Page<LibraryAcknowledgement> acknowledged,
                                      Page<User> notAcknowledged, ListQuery readList, ListQuery notReadList,
                                      Instant nextReminderAt) {

    // By full name: there is nothing to sort, but ListQuery needs its default.
    private static final ListSort BY_NAME = ListSort.asc("name");

    /** "Read" (path: the overview's URL; ?page=), keeping the page of "Not read yet" (?notReadPage=) in its links. */
    public static ListQuery readList(String path, ListRequest request, Integer notReadPage) {
        return ListQuery.firstOfTwo(path, request, "notReadPage", notReadPage, "ackRead", BY_NAME);
    }

    /** "Not read yet" at its page, keeping the page of "Read" in its links. */
    public static ListQuery notReadList(String path, ListRequest request, Integer notReadPage) {
        return ListQuery.secondOfTwo(path, request, "notReadPage", notReadPage, "ackNotRead", BY_NAME);
    }
}
