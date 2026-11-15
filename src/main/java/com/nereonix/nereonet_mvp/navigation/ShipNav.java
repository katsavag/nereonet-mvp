package com.nereonix.nereonet_mvp.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.nereonix.nereonet_mvp.core.user.Role;

/**
 * The ship user's navigation, the one place that decides it per role:
 *   onBoard   the sidebar's "On board" group: Home, News, Library, My crew (every ship role).
 *   supplies  the sidebar's "Supplies" group, in the order of the work: Requests (Chief cook, crew members), Approvals
 *             (Master), Vessel order (Master, Chief cook), Stores (Chief cook).
 *   tabs      the phone tab bar before More: Home first, then the Master's Approvals and Vessel order, the Chief cook's
 *             Stores and Requests, or the other crew's News and Library.
 * Whatever is not a tab is on the More page under the same two headings (moreOnBoard, moreSupplies; never Home), and
 * the More tab's counter adds up their counters (moreBase) plus the unread notifications (added by the layout).
 */
public record ShipNav(List<Entry> onBoard, List<Entry> supplies, List<Entry> tabs) {

    /**
     * One entry: section is its NavSection key (and icon), url its page, labelKey its label (nav.*), count its counter
     * (null for none) and countKey the counter in words for screen readers (nav.count.*, {0} the number), or null.
     */
    public record Entry(String section, String url, String labelKey, Long count, String countKey) {

        /** The number shown, 0 for none. */
        public long countValue() {
            return count == null ? 0 : count;
        }
    }

    /** The counters behind the entries, each null for a role that does not see it (NavigationAdvice). */
    record Counts(Long announcements, Long requests, Long approvals, Long orders, Long stores) {
    }

    static ShipNav of(Role role, Counts c) {
        List<Entry> onBoard = List.of(
                new Entry("home", "/ship", "nav.home", null, null),
                new Entry("announcements", "/ship/news", "nav.news", c.announcements(), "nav.count.announcements"),
                new Entry("library", "/ship/library", "nav.library", null, null),
                new Entry("crew", "/ship/crew", "nav.myCrew", null, null));
        List<Entry> supplies = new ArrayList<>();
        if (role == Role.CHIEF_COOK || role == Role.CREW_MEMBER) {
            supplies.add(new Entry("requests", "/ship/requests", "nav.requests", c.requests(), "nav.count.requests"));
        }
        if (role == Role.MASTER) {
            supplies.add(new Entry("approvals", "/ship/approvals", "nav.approvals", c.approvals(), "nav.count.approvals"));
        }
        if (role == Role.MASTER || role == Role.CHIEF_COOK) {
            // The Chief cook reads the vessel order without its counter (orders is null for them).
            supplies.add(new Entry("order", "/ship/order", "nav.order", c.orders(), "nav.count.order"));
        }
        if (role == Role.CHIEF_COOK) {
            supplies.add(new Entry("stores", "/ship/stores", "nav.stores", c.stores(), "nav.count.stores"));
        }
        List<String> tabKeys = switch (role) {
            case MASTER -> List.of("home", "approvals", "order");
            case CHIEF_COOK -> List.of("home", "stores", "requests");
            default -> List.of("home", "announcements", "library");
        };
        List<Entry> all = new ArrayList<>(onBoard);
        all.addAll(supplies);
        List<Entry> tabs = tabKeys.stream()
                .map(key -> all.stream().filter(e -> e.section().equals(key)).findFirst().orElseThrow())
                .toList();
        return new ShipNav(onBoard, List.copyOf(supplies), tabs);
    }

    /** The On board entries under More: not a tab, never Home. */
    public List<Entry> moreOnBoard() {
        return onBoard.stream().filter(e -> !isTab(e.section())).toList();
    }

    /** The Supplies entries under More: not a tab. */
    public List<Entry> moreSupplies() {
        return supplies.stream().filter(e -> !isTab(e.section())).toList();
    }

    /** The More tab's counter without the unread notifications: the counters of the entries under More. */
    public long moreBase() {
        return moreOnBoard().stream().mapToLong(Entry::countValue).sum()
                + moreSupplies().stream().mapToLong(Entry::countValue).sum();
    }

    /** The active tab on a page of this section: the section's own tab, else More (My profile, Notifications, More
     *  itself and every entry that is not a tab); null for a page with no section. */
    public String tabOf(NavSection section) {
        if (section == null) {
            return null;
        }
        return isTab(section.key()) ? section.key() : NavSection.MORE.key();
    }

    private boolean isTab(String section) {
        return tabs.stream().anyMatch(t -> Objects.equals(t.section(), section));
    }
}
