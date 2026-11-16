package com.nereonix.nereonet_mvp.core.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;

/**
 * The rows of a delivery count: what the Chief cook counts and the Master approves, built from the order's lines
 * and the invoice's lines as matched (InvoiceMatchService). One row per order line not removed by the office, and one
 * per invoice line matched to a vessel item not on the order. With a read invoice each row's reference is the
 * invoice's quantity, counted in the invoice's unit ("Basmati rice, 10 kg bag · Invoice: 12 bags"); an order line
 * the invoice does not have is "Not on the invoice", an invoice quantity not read "Invoice: not read". Without a read
 * invoice the reference is the quantity ordered. picks: the invoice lines nothing was found for (the cook chooses);
 * notOrdered: the ones he said were not ordered; removed: the ones he removed (DeliveryInvoiceLine.removedOnBoard),
 * which are in no row and no total. Quantities are kept in the item's unit; a row shows them in its own.
 */
public final class CountSheet {

    /** Where a row's reference comes from. */
    public enum Source {
        /** The invoice's quantity. */
        INVOICE,
        /** On the invoice, but its quantity was not read or cannot be converted. */
        NOT_READ,
        /** An order line the invoice does not have. */
        NOT_ON_INVOICE,
        /** No invoice read: the quantity ordered. */
        NO_INVOICE
    }

    /** One row: an order line (orderLine) or an invoice line matched to an item not on the order (extra). */
    public static final class Row {
        private final String key;
        private final Item item;
        private final ShipOrderLine orderLine;
        private final DeliveryInvoiceLine extra;
        private final List<DeliveryInvoiceLine> invoiceLines;
        private final BigDecimal factor;
        private final String pack;
        private final String unitOne;
        private final String unitMany;
        private final BigDecimal invoiced;
        private final Source source;

        Row(String key, Item item, ShipOrderLine orderLine, DeliveryInvoiceLine extra,
            List<DeliveryInvoiceLine> invoiceLines, BigDecimal factor, String pack, String unitOne, String unitMany,
            BigDecimal invoiced, Source source) {
            this.key = key;
            this.item = item;
            this.orderLine = orderLine;
            this.extra = extra;
            this.invoiceLines = invoiceLines;
            this.factor = factor;
            this.pack = pack;
            this.unitOne = unitOne;
            this.unitMany = unitMany;
            this.invoiced = invoiced;
            this.source = source;
        }

        /** "o12" for order line 12, "i34" for invoice line 34. */
        public String getKey() { return key; }
        public Item getItem() { return item; }
        public ShipOrderLine getOrderLine() { return orderLine; }
        public DeliveryInvoiceLine getExtra() { return extra; }
        /** The invoice lines matched to it (none for an order line not on the invoice). */
        public List<DeliveryInvoiceLine> getInvoiceLines() { return invoiceLines; }
        public Source getSource() { return source; }
        /** One unit of the row is this many of the item's unit (1 when the row counts in the item's unit). */
        public BigDecimal getFactor() { return factor; }

        /** "Basmati rice, 10 kg bag", or the item's name. */
        public String getName() {
            return pack != null ? item.getName() + ", " + pack + " " + unitOne : item.getName();
        }

        /** The row counts in the invoice's unit, not the item's. */
        public boolean isPacked() {
            return unitOne != null;
        }

        /** The unit beside the input: "bags", or the item's unit. */
        public String getUnit() {
            return isPacked() ? unitMany : item.getUnit();
        }

        /** The unit for a quantity: "1 bag", "12 bags", "25 kg". */
        public String unit(BigDecimal quantity) {
            return isPacked() && quantity != null && quantity.compareTo(BigDecimal.ONE) == 0 ? unitOne : getUnit();
        }

        /** The invoice's quantity in the row's unit; null when not on the invoice or not read. */
        public BigDecimal getInvoiced() { return invoiced; }

        /** The quantity ordered (the item's unit); null for an item not on the order. */
        public BigDecimal getOrdered() {
            return orderLine == null ? null : orderLine.getQuantity();
        }

        /** The quantity ordered as the reference line shows it: in the row's unit when that is exact, else the item's. */
        public BigDecimal getOrderedShown() {
            return orderedInRow() ? toRow(getOrdered()) : getOrdered();
        }

        /** The unit of getOrderedShown. */
        public String getOrderedUnit() {
            return orderedInRow() ? unit(toRow(getOrdered())) : item.getUnit();
        }

        private boolean orderedInRow() {
            return isPacked() && getOrdered() != null && toItem(toRow(getOrdered())).compareTo(getOrdered()) == 0;
        }

        /**
         * The invoice line whose quantity the cook can correct on this row ("Read wrong?"): the one line matched to
         * it, when its unit converts. Null for a row not on the invoice or made of several lines.
         */
        public DeliveryInvoiceLine getInvoiceLine() {
            return invoiceLines.size() == 1 && invoiceLines.getFirst().getPackFactor() != null
                    ? invoiceLines.getFirst() : null;
        }

        /** The invoice line's quantity for one in the row's unit (what the cook says the paper invoice says). */
        public BigDecimal toInvoice(BigDecimal rowQuantity) {
            return isPacked() ? rowQuantity
                    : rowQuantity.divide(getInvoiceLine().getPackFactor(), 3, RoundingMode.HALF_UP);
        }

        /** A person corrected the invoice's quantity: "(corrected, photo read 12)". */
        public boolean isCorrected() {
            return invoiceLines.stream().anyMatch(DeliveryInvoiceLine::isQuantityCorrected);
        }

        /** The Chief cook added an invoice line of this row by hand: "(added by hand)". */
        public boolean isAddedByHand() {
            return invoiceLines.stream().anyMatch(DeliveryInvoiceLine::isAddedOnBoard);
        }

        /** What the photo read where a person corrected it, in the row's unit; null when it read no quantity. */
        public BigDecimal getPhotoRead() {
            return sum(invoiceLines.stream().map(line -> {
                BigDecimal read = line.isQuantityCorrected() ? line.getReadQuantity() : line.getQuantity();
                return read == null || isPacked() ? read
                        : line.getPackFactor() == null ? null : read.multiply(line.getPackFactor());
            }).toList());
        }

        /** What "Arrived" is prefilled with: the invoice's quantity, or without an invoice the quantity ordered. */
        public BigDecimal getReference() {
            return switch (source) {
                case INVOICE -> invoiced;
                case NO_INVOICE -> getOrdered();
                default -> null;
            };
        }

        /** What the count is compared with: the reference, 0 for a line not on the invoice. */
        public BigDecimal getBase() {
            return switch (source) {
                case INVOICE -> invoiced;
                case NO_INVOICE -> getOrdered();
                case NOT_ON_INVOICE -> BigDecimal.ZERO;
                case NOT_READ -> null;
            };
        }

        /** What the cook counted, in the item's unit (null: not counted). */
        public BigDecimal getCountedItem() {
            return orderLine != null ? orderLine.getCountedQuantity() : extra.getCountedQuantity();
        }

        /** The Master's change, in the item's unit (null: as counted). */
        public BigDecimal getApprovedItem() {
            return orderLine != null ? orderLine.getApprovedQuantity() : extra.getApprovedQuantity();
        }

        public String getNote() {
            return orderLine != null ? orderLine.getCountNote() : extra.getCountNote();
        }

        public BigDecimal getCounted() {
            return toRow(getCountedItem());
        }

        public BigDecimal getApproved() {
            return toRow(getApprovedItem());
        }

        /** What arrived as it stands: the Master's change, or the count; null when not counted. */
        public BigDecimal getResult() {
            return getApproved() != null ? getApproved() : getCounted();
        }

        /** As getResult, in the item's unit. */
        public BigDecimal getResultItem() {
            return getApprovedItem() != null ? getApprovedItem() : getCountedItem();
        }

        public boolean hasCount() {
            return getCountedItem() != null;
        }

        /** The Master changed the cook's count ("Changed from 23"). */
        public boolean isChanged() {
            return getApprovedItem() != null && !Objects.equals(compare(getApprovedItem(), getCountedItem()), 0);
        }

        /** Not as the invoice (or, without one, the order) says: different, not counted, or nothing to compare. */
        public boolean isDifferent() {
            BigDecimal result = getResult();
            BigDecimal base = getBase();
            return result == null || base == null || result.compareTo(base) != 0;
        }

        /** The quantity in the row's unit for one in the item's. */
        public BigDecimal toRow(BigDecimal itemQuantity) {
            return itemQuantity == null ? null
                    : itemQuantity.divide(factor, 3, RoundingMode.HALF_UP).stripTrailingZeros();
        }

        /** The quantity in the item's unit for one in the row's. */
        public BigDecimal toItem(BigDecimal rowQuantity) {
            BigDecimal value = rowQuantity.multiply(factor).stripTrailingZeros();
            return value.scale() < 0 ? value.setScale(0) : value;
        }

        /** Any matched invoice line is an uncertain match (the office's "match to review"). */
        public boolean isMatchToReview() {
            return invoiceLines.stream().anyMatch(line -> line.getMatch() == InvoiceMatch.CHECK);
        }

        public boolean isConversionToReview() {
            return invoiceLines.stream().anyMatch(DeliveryInvoiceLine::isConversionToReview);
        }

        /** A quantity as the page writes it: 12, 2.5 (never 12.000 or 1E+1); empty for none. */
        public String text(BigDecimal quantity) {
            if (quantity == null) {
                return "";
            }
            BigDecimal value = quantity.stripTrailingZeros();
            return (value.scale() < 0 ? value.setScale(0) : value).toPlainString();
        }

        /**
         * How the page marks a number typed in the row's input (the row's unit): "empty" for none, "same" when it is
         * what the invoice (or the order) says, "different" when it is not or there is nothing to compare with. Text
         * that is not a number is "same": its error says what is wrong.
         */
        public String mark(String typed) {
            if (typed == null || typed.isBlank()) {
                return "empty";
            }
            try {
                BigDecimal value = new BigDecimal(typed.strip().replace(',', '.'));
                return getBase() != null && value.compareTo(getBase()) == 0 ? "same" : "different";
            } catch (NumberFormatException e) {
                return "same";
            }
        }

        /** The anchor of the row on the page. */
        public String getAnchor() {
            return "row-" + key;
        }

        private static Integer compare(BigDecimal a, BigDecimal b) {
            return a == null || b == null ? null : a.compareTo(b);
        }
    }

    /** The rows of one category. */
    public record Group(ItemCategory category, List<Row> rows) {
    }

    private final List<Row> rows;
    private final List<DeliveryInvoiceLine> picks;
    private final List<DeliveryInvoiceLine> notOrdered;
    private final List<DeliveryInvoiceLine> removed;
    private final boolean invoiceRead;

    private CountSheet(List<Row> rows, List<DeliveryInvoiceLine> picks, List<DeliveryInvoiceLine> notOrdered,
                       List<DeliveryInvoiceLine> removed, boolean invoiceRead) {
        this.rows = rows;
        this.picks = picks;
        this.notOrdered = notOrdered;
        this.removed = removed;
        this.invoiceRead = invoiceRead;
    }

    /** True when the invoice was read (some lines at least): the rows count against it. */
    public static boolean read(DeliveryInvoice invoice) {
        return invoice != null && (invoice.getStatus() == DeliveryInvoiceStatus.READ
                || invoice.getStatus() == DeliveryInvoiceStatus.PARTLY_READ);
    }

    /** The sheet of an order (CONFIRMED), with its invoice (null: none) and that invoice's lines as matched. */
    public static CountSheet of(ShipOrder order, DeliveryInvoice invoice, List<DeliveryInvoiceLine> allLines) {
        boolean read = read(invoice) && !allLines.isEmpty();
        List<DeliveryInvoiceLine> removed = allLines.stream().filter(DeliveryInvoiceLine::isRemovedOnBoard).toList();
        List<DeliveryInvoiceLine> invoiceLines = allLines.stream().filter(l -> !l.isRemovedOnBoard()).toList();
        List<Row> rows = new ArrayList<>();
        List<DeliveryInvoiceLine> picks = new ArrayList<>();
        List<DeliveryInvoiceLine> notOrdered = new ArrayList<>();
        for (ShipOrderLine line : order.getLines()) {
            if (line.isRemoved()) {
                continue;
            }
            if (!read) {
                rows.add(new Row("o" + line.getId(), line.getItem(), line, null, List.of(), BigDecimal.ONE, null, null,
                        null, null, Source.NO_INVOICE));
                continue;
            }
            List<DeliveryInvoiceLine> matched = invoiceLines.stream()
                    .filter(l -> l.getMatch() != null && l.getMatch().linked() && l.getOrderLine() != null
                            && l.getOrderLine().getId().equals(line.getId()))
                    .toList();
            rows.add(row("o" + line.getId(), line.getItem(), line, null, matched));
        }
        if (read) {
            for (DeliveryInvoiceLine l : invoiceLines) {
                if (l.getMatch() == null || l.getMatch() == InvoiceMatch.PICK) {
                    picks.add(l);
                } else if (l.getMatch() == InvoiceMatch.NOT_ORDERED) {
                    notOrdered.add(l);
                } else if (l.getOrderLine() == null && l.getItem() != null) {
                    rows.add(row("i" + l.getId(), l.getItem(), null, l, List.of(l)));
                }
            }
        }
        rows.sort(Comparator.comparing((Row row) -> row.getItem().getCategory())
                .thenComparing(row -> row.getItem().getName(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Row::getKey));
        return new CountSheet(List.copyOf(rows), List.copyOf(picks), List.copyOf(notOrdered), read ? removed : List.of(),
                read);
    }

    // A row against the invoice lines matched to it: in the invoice's unit when they all share one, else the item's.
    private static Row row(String key, Item item, ShipOrderLine orderLine, DeliveryInvoiceLine extra,
                           List<DeliveryInvoiceLine> matched) {
        if (matched.isEmpty()) {
            return new Row(key, item, orderLine, extra, matched, BigDecimal.ONE, null, null, null, null,
                    Source.NOT_ON_INVOICE);
        }
        DeliveryInvoiceLine first = matched.getFirst();
        boolean sameUnit = first.getPackFactor() != null && matched.stream().allMatch(l -> l.getPackFactor() != null
                && l.getPackFactor().compareTo(first.getPackFactor()) == 0
                && Objects.equals(l.getPackUnitOne(), first.getPackUnitOne()) && Objects.equals(l.getPack(), first.getPack()));
        if (sameUnit && first.getPackUnitOne() != null) {
            BigDecimal invoiced = sum(matched.stream().map(DeliveryInvoiceLine::getQuantity).toList());
            return new Row(key, item, orderLine, extra, matched, first.getPackFactor(), first.getPack(),
                    first.getPackUnitOne(), first.getPackUnitMany(), invoiced,
                    invoiced == null ? Source.NOT_READ : Source.INVOICE);
        }
        BigDecimal invoiced = sum(matched.stream().map(DeliveryInvoiceLine::getItemQuantity).toList());
        return new Row(key, item, orderLine, extra, matched, BigDecimal.ONE, null, null, null, invoiced,
                invoiced == null ? Source.NOT_READ : Source.INVOICE);
    }

    // The sum, or null when any is missing.
    private static BigDecimal sum(List<BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            if (value == null) {
                return null;
            }
            total = total.add(value);
        }
        return total.stripTrailingZeros().scale() < 0 ? total.setScale(0) : total.stripTrailingZeros();
    }

    public List<Row> getRows() { return rows; }

    /** The invoice lines nothing was found for: "On the invoice, not found on the order". */
    public List<DeliveryInvoiceLine> getPicks() { return picks; }

    public List<DeliveryInvoiceLine> getNotOrdered() { return notOrdered; }

    /** The invoice lines the Chief cook removed, as the photo read them: "Removed invoice lines". */
    public List<DeliveryInvoiceLine> getRemoved() { return removed; }

    /** The invoice lines the Chief cook added by hand, in the sheet's order. */
    public List<DeliveryInvoiceLine> getAdded() {
        return rows.stream().flatMap(row -> row.getInvoiceLines().stream())
                .filter(DeliveryInvoiceLine::isAddedOnBoard).toList();
    }

    /** The row this invoice line is part of, or null (a line to choose, not ordered, or removed). */
    public Row rowOf(Long invoiceLineId) {
        return rows.stream().filter(row -> row.getInvoiceLines().stream().anyMatch(l -> l.getId().equals(invoiceLineId)))
                .findFirst().orElse(null);
    }

    public boolean isInvoiceRead() { return invoiceRead; }

    /** The row with this key, or null. */
    public Row row(String key) {
        return rows.stream().filter(row -> row.getKey().equals(key)).findFirst().orElse(null);
    }

    public List<Row> getToDo() {
        return rows.stream().filter(row -> !row.hasCount()).toList();
    }

    public List<Row> getDone() {
        return rows.stream().filter(Row::hasCount).toList();
    }

    /** Every product to count: the rows and the invoice lines still to choose. */
    public int getTotal() {
        return rows.size() + picks.size();
    }

    public int getCountedTotal() {
        return getDone().size();
    }

    /** Still to do: the rows not counted and the products to choose. */
    public int getToDoTotal() {
        return getTotal() - getCountedTotal();
    }

    /** The rows not as the invoice (or the order) says, in the sheet's order. */
    public List<Row> getDifferent() {
        return rows.stream().filter(Row::isDifferent).toList();
    }

    /** The rows whose invoice quantity a person corrected, in the sheet's order. */
    public List<Row> getCorrected() {
        return rows.stream().filter(Row::isCorrected).toList();
    }

    /** The rows counted as the invoice (or the order) says. */
    public int getSameTotal() {
        return rows.size() - getDifferent().size();
    }

    /** The products that differ: the rows and the products never chosen. */
    public int getDifferentTotal() {
        return getDifferent().size() + picks.size();
    }

    /** The rows by category (provisions first), by name within one. */
    public static List<Group> groups(List<Row> rows) {
        Map<ItemCategory, List<Row>> byCategory = new EnumMap<>(ItemCategory.class);
        rows.forEach(row -> byCategory.computeIfAbsent(row.getItem().getCategory(), c -> new ArrayList<>()).add(row));
        return byCategory.entrySet().stream().map(e -> new Group(e.getKey(), List.copyOf(e.getValue()))).toList();
    }
}
