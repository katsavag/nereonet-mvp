package com.nereonix.nereonet_mvp.core.list;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Cards split into two columns that stand side by side (app.css .card-columns): the first items on the left, the
 * rest on the right, cut where the two sides are closest in weight. The order is kept, so read left then right the
 * items are in their original order. One item (or none) stays on the left and the right is empty.
 */
public record TwoColumns<T>(List<T> left, List<T> right) {

    /** Splits the items by weight (for a card: what makes it tall); on a tie the left column is the shorter list. */
    public static <T> TwoColumns<T> split(List<T> items, ToIntFunction<? super T> weight) {
        if (items.size() < 2) {
            return new TwoColumns<>(List.copyOf(items), List.of());
        }
        long total = 0;
        for (T item : items) {
            total += weight.applyAsInt(item);
        }
        int cut = 1;
        long best = Long.MAX_VALUE;
        long left = 0;
        for (int i = 1; i < items.size(); i++) {
            left += weight.applyAsInt(items.get(i - 1));
            long difference = Math.abs(total - 2 * left);
            if (difference < best) {
                best = difference;
                cut = i;
            }
        }
        return new TwoColumns<>(List.copyOf(items.subList(0, cut)), List.copyOf(items.subList(cut, items.size())));
    }

    /** Both columns, left first, leaving out an empty one. */
    public List<List<T>> columns() {
        List<List<T>> columns = new ArrayList<>(2);
        if (!left.isEmpty()) {
            columns.add(left);
        }
        if (!right.isEmpty()) {
            columns.add(right);
        }
        return columns;
    }
}
