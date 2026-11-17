package com.nereonix.nereonet_mvp.core.list;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The split of cards into two columns, closest in weight, order kept. */
class TwoColumnsTest {

    @Test
    void theCutIsWhereTheTwoSidesAreClosest() {
        // Cold room 12, Dry store 28, Freezer 9, Other 4 rows, each weighing its rows plus 2.
        TwoColumns<Integer> columns = TwoColumns.split(List.of(12, 28, 9, 4), rows -> rows + 2);
        assertThat(columns.left()).containsExactly(12, 28);
        assertThat(columns.right()).containsExactly(9, 4);
    }

    @Test
    void theOrderIsKeptAndEveryItemIsInOneColumn() {
        TwoColumns<Integer> columns = TwoColumns.split(List.of(1, 1, 1, 1, 10), w -> w);
        assertThat(columns.left()).containsExactly(1, 1, 1, 1);
        assertThat(columns.right()).containsExactly(10);
        assertThat(columns.columns()).hasSize(2);
    }

    @Test
    void onATieTheLeftIsTheShorterList() {
        TwoColumns<Integer> columns = TwoColumns.split(List.of(5, 5, 5, 5, 5, 5), w -> w);
        assertThat(columns.left()).containsExactly(5, 5, 5);
        TwoColumns<Integer> odd = TwoColumns.split(List.of(5, 5, 5), w -> w);
        assertThat(odd.left()).containsExactly(5);
        assertThat(odd.right()).containsExactly(5, 5);
    }

    @Test
    void oneItemOrNoneStaysOnTheLeft() {
        TwoColumns<Integer> one = TwoColumns.split(List.of(7), w -> w);
        assertThat(one.left()).containsExactly(7);
        assertThat(one.right()).isEmpty();
        assertThat(one.columns()).hasSize(1);
        assertThat(TwoColumns.split(List.<Integer>of(), w -> w).columns()).isEmpty();
    }
}
