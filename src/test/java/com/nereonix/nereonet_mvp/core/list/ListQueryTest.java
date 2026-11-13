package com.nereonix.nereonet_mvp.core.list;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/** P19: the list state read from the URL and every link built from it. */
class ListQueryTest {

    private static final ListSort[] SORTS = {ListSort.asc("name"), ListSort.desc("sentAt")};

    @Test
    void anUnknownOrMissingValueFallsBackToTheDefault() {
        ListQuery list = ListQuery.of("/office/crew", new ListRequest("  ", "role", "sideways", -3, 30), SORTS);
        assertThat(list.getQ()).isNull();
        assertThat(list.getSort()).isEqualTo("name");
        assertThat(list.isDescending()).isFalse();
        assertThat(list.getPage()).isZero();
        // P43: 10 a page by default, on every list; 20, 50 and 100 on request.
        assertThat(list.getSize()).isEqualTo(10);
        assertThat(ListQuery.DEFAULT_SIZE).isEqualTo(10);
        assertThat(list.getSizes()).containsExactly(10, 20, 50, 100);
        assertThat(ListQuery.of("/x", new ListRequest(null, null, null, null, 25), SORTS).getSize()).isEqualTo(10);
        assertThat(ListQuery.of("/x", new ListRequest(null, null, null, null, 100), SORTS).getSize()).isEqualTo(100);
        assertThat(list.isFiltered()).isFalse();
        assertThat(list.getUrl()).isEqualTo("/office/crew");

        // A column whose first direction is descending (dates: newest first).
        assertThat(ListQuery.of("/office/announcements", new ListRequest(null, "sentAt", null, null, null), SORTS)
                .isDescending()).isTrue();
    }

    @Test
    void linksKeepTheStateInAFixedOrderAndStartAgainAtPageOne() {
        ListQuery list = ListQuery.of("/office/crew", new ListRequest(" Jane & co+ ", "name", "desc", 3, 50), SORTS)
                .filter("shipId", 7L, "list.filter.ship", "MV Demo")
                .filterKey("status", "expired", "user.status", "crew.filter.status.expired");

        assertThat(list.getQ()).isEqualTo("Jane & co+");
        assertThat(list.getUrl())
                .isEqualTo("/office/crew?q=Jane+%26+co%2B&shipId=7&status=expired&sort=name&dir=desc&size=50&page=3");
        // Sorting the sorted column again flips it; another column starts with its own direction. Both: page 1.
        assertThat(list.sortUrl("name"))
                .isEqualTo("/office/crew?q=Jane+%26+co%2B&shipId=7&status=expired&sort=name&dir=asc&size=50");
        assertThat(list.sortUrl("sentAt"))
                .isEqualTo("/office/crew?q=Jane+%26+co%2B&shipId=7&status=expired&sort=sentAt&dir=desc&size=50");
        assertThat(list.ariaSort("name")).isEqualTo("descending");
        assertThat(list.ariaSort("sentAt")).isNull();
        assertThat(list.pageUrl(0))
                .isEqualTo("/office/crew?q=Jane+%26+co%2B&shipId=7&status=expired&sort=name&dir=desc&size=50");
        assertThat(list.sizeUrl(10)).isEqualTo("/office/crew?q=Jane+%26+co%2B&shipId=7&status=expired&sort=name&dir=desc");

        // The chips, each removing only itself; Clear all keeps sort and size.
        assertThat(list.getChips()).extracting(ListQuery.Chip::removeUrl).containsExactly(
                "/office/crew?shipId=7&status=expired&sort=name&dir=desc&size=50",
                "/office/crew?q=Jane+%26+co%2B&status=expired&sort=name&dir=desc&size=50",
                "/office/crew?q=Jane+%26+co%2B&shipId=7&sort=name&dir=desc&size=50");
        assertThat(list.getChips()).extracting(ListQuery.Chip::labelKey)
                .containsExactly("list.search.chip", "list.filter.ship", "user.status");
        assertThat(list.getClearUrl()).isEqualTo("/office/crew?sort=name&dir=desc&size=50");
        // The filter form sends sort and size along, never the page.
        assertThat(list.getHidden()).containsExactly(
                java.util.Map.entry("sort", "name"), java.util.Map.entry("dir", "desc"), java.util.Map.entry("size", "50"));
        assertThat(list.getRequest()).isEqualTo(new ListRequest("Jane & co+", "name", "desc", 3, 50));
    }

    @Test
    void theSearchIsALikePatternWithItsWildcardsEscaped() {
        ListQuery list = ListQuery.of("/office/crew", new ListRequest("50%_OFF\\", null, null, null, null), SORTS);
        assertThat(list.getContains()).isEqualTo("%50\\%\\_off\\\\%");
        assertThat(ListQuery.of("/x", new ListRequest("a".repeat(150), null, null, null, null), SORTS).getQ())
                .hasSize(100);
    }

    @Test
    void theRangeAndThePager() {
        ListQuery list = ListQuery.of("/office/crew", new ListRequest(null, null, null, 2, null), SORTS);
        var secondPage = new PageImpl<>(List.of("a", "b"), PageRequest.of(1, 10), 12);
        assertThat(list.first(secondPage)).isEqualTo(11);
        assertThat(list.last(secondPage)).isEqualTo(12);
        assertThat(list.pagerShown(secondPage)).isTrue();
        assertThat(list.pagerShown(new PageImpl<>(List.of("a"), PageRequest.of(0, 10), 10))).isFalse();
        // A page past the end shows the last one.
        assertThat(ListQueries.clamp(ListQuery.of("/x", new ListRequest(null, null, null, 9, null), SORTS), 27))
                .isEqualTo(2);
        assertThat(ListQueries.clamp(list, 0)).isZero();
    }
}
