package com.nereonix.nereonet_mvp.core.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.nereonix.nereonet_mvp.core.item.Item;
import com.nereonix.nereonet_mvp.core.item.ItemCategory;

/**
 * Order, delivery and invoice side by side, for the office, once the Master confirmed receipt of an order with a
 * read invoice. Pure: built from the order's lines (ordered, received), the invoice's lines as matched (the
 * invoiced quantity in the item's unit, the invoice's unit price and amount), the awarded quote's prices per item unit
 * (empty without an award) and the office's decisions (InvoiceDecision).
 * <ul>
 *   <li>One row per order line not removed by the office, and one per invoice line linked to no order line (Not
 *       ordered: chosen so by the cook, or never chosen). A row differs when anything of {@link DiffKind} applies; a
 *       difference needs a decision (Accept with a note) before the order can be marked matched, and a decision holds
 *       only while the difference stays what it was (detail).</li>
 *   <li>The points to review (the things the ship was deliberately not asked about): the invoice's checks
 *       that failed, a different order reference, lines read with a flag, matches and pack conversions to review.
 *       Each needs Correct or Change.</li>
 * </ul>
 * Quantities are in the item's unit (an invoice line linked to nothing: the invoice's unit); amounts in the invoice's
 * currency, which is the awarded quote's when there is one (null: shown without a code).
 */
public final class InvoiceComparison {

    /** A quoted price differs when it is more than this share apart (1%). */
    static final BigDecimal PRICE_TOLERANCE = new BigDecimal("0.01");

    /** What differs on a row, in the order the page says them. */
    public enum DiffKind {
        /** On the order, not on the invoice (ordered or received above zero). */
        NOT_ON_INVOICE,
        /** On the invoice, not ordered: an invoice line linked to no order line, or a line added on receipt. */
        NOT_ORDERED,
        /** On the invoice, but its quantity was not read or cannot be put in the item's unit. */
        INVOICE_NOT_READ,
        INVOICED_MORE_THAN_ORDERED,
        INVOICED_LESS_THAN_ORDERED,
        /** A credit to claim. */
        RECEIVED_LESS_THAN_INVOICED,
        RECEIVED_MORE_THAN_INVOICED,
        /** Not on the invoice: what arrived against what was ordered. */
        RECEIVED_LESS_THAN_ORDERED,
        RECEIVED_MORE_THAN_ORDERED,
        /** The invoice's price per item unit more than 1% above the quote's. */
        PRICE_ABOVE_QUOTE,
        PRICE_BELOW_QUOTE;

        public String labelKey() {
            return "officeorder.compare.diff." + name();
        }

        public boolean price() {
            return this == PRICE_ABOVE_QUOTE || this == PRICE_BELOW_QUOTE;
        }
    }

    /** One difference: what, and by how much (a quantity in the row's unit, or a price per unit), never negative. */
    public record Diff(DiffKind kind, BigDecimal amount) {

        String detail() {
            return kind.name() + (amount == null ? "" : ":" + amount.stripTrailingZeros().toPlainString());
        }
    }

    /** The kinds of point to review, in the order the page lists them. */
    public enum ReviewKind {
        /** An invoice check that failed ("Page 1: the lines add up to 1,759.50, the page says 1,773.70"). */
        CHECK,
        /** The invoice's order reference is not this order's. */
        REFERENCE,
        /** A line read with a flag: corrected, hard to read (check), quantity not read. */
        LINE,
        /** A product match to review ("FISH FILLET HAKE -> Fish fillets"). */
        MATCH,
        /** A pack conversion to review ("RICE 10KG, BAG -> x 10 kg"). */
        CONVERSION
    }

    /** A point to review: key (InvoiceDecision.itemKey), its text (CHECK, REFERENCE) or invoice line, the decision. */
    public record ReviewItem(String key, ReviewKind kind, String text, DeliveryInvoiceLine line,
                             InvoiceDecision decision) {

        public boolean resolved() {
            return decision != null;
        }

        /** Correct and Change both apply (a match, a conversion, a line); a check or the reference only Correct. */
        public boolean changeable() {
            return line != null;
        }
    }

    /** One category's rows ("Provisions: 24 lines, all agree"); category null: the invoice lines not ordered. */
    public record CategorySummary(ItemCategory category, int lines, int differ) {
    }

    /** One row: an order line (orderLine) or an invoice line linked to none (the first of invoiceLines). */
    public static final class Row {
        private final String key;
        private final Item item;
        private final ShipOrderLine orderLine;
        private final List<DeliveryInvoiceLine> invoiceLines;
        private final BigDecimal invoiced;
        private final BigDecimal unitPrice;
        private final BigDecimal amount;
        private final BigDecimal pricePerUnit;
        private final BigDecimal quoted;
        private final List<Diff> diffs;
        private final BigDecimal stake;
        private final InvoiceDecision decision;

        Row(String key, Item item, ShipOrderLine orderLine, List<DeliveryInvoiceLine> invoiceLines, BigDecimal quoted,
            InvoiceDecision decision) {
            this.key = key;
            this.item = item;
            this.orderLine = orderLine;
            this.invoiceLines = invoiceLines;
            this.quoted = quoted;
            this.invoiced = invoiceLines.isEmpty() ? null
                    : sum(invoiceLines.stream().map(l -> orderLine == null && item == null ? l.getQuantity()
                            : l.getItemQuantity()).toList());
            this.amount = invoiceLines.isEmpty() ? null : sum(invoiceLines.stream().map(DeliveryInvoiceLine::getAmount).toList());
            BigDecimal price = invoiceLines.isEmpty() ? null : invoiceLines.getFirst().getUnitPrice();
            BigDecimal factor = invoiceLines.isEmpty() ? null : invoiceLines.getFirst().getPackFactor();
            boolean samePrice = price != null && invoiceLines.stream().allMatch(l -> l.getUnitPrice() != null
                    && l.getUnitPrice().compareTo(price) == 0 && Objects.equals(l.getPackFactor(), factor));
            this.unitPrice = samePrice ? price : null;
            if (!samePrice) {
                this.pricePerUnit = null;
            } else if (orderLine == null && item == null) {
                // Linked to nothing: the invoice's own unit.
                this.pricePerUnit = price;
            } else {
                this.pricePerUnit = factor != null && factor.signum() > 0 ? price.divide(factor, 4, RoundingMode.HALF_UP)
                        : null;
            }
            this.diffs = diffs();
            this.stake = stake();
            this.decision = decision;
        }

        private List<Diff> diffs() {
            List<Diff> found = new ArrayList<>();
            if (orderLine == null) {
                found.add(new Diff(DiffKind.NOT_ORDERED, invoiced));
                return found;
            }
            BigDecimal ordered = orderLine.getQuantity();
            BigDecimal received = getReceived();
            if (invoiceLines.isEmpty()) {
                if (ordered.signum() > 0 || received.signum() > 0) {
                    found.add(new Diff(DiffKind.NOT_ON_INVOICE, null));
                }
                compare(found, received, ordered, DiffKind.RECEIVED_MORE_THAN_ORDERED, DiffKind.RECEIVED_LESS_THAN_ORDERED);
                return found;
            }
            if (orderLine.isAddedOnReceipt() || ordered.signum() == 0) {
                found.add(new Diff(DiffKind.NOT_ORDERED, invoiced));
            }
            if (invoiced == null) {
                found.add(new Diff(DiffKind.INVOICE_NOT_READ, null));
                if (!orderLine.isAddedOnReceipt()) {
                    compare(found, received, ordered, DiffKind.RECEIVED_MORE_THAN_ORDERED,
                            DiffKind.RECEIVED_LESS_THAN_ORDERED);
                }
            } else {
                if (!orderLine.isAddedOnReceipt() && ordered.signum() > 0) {
                    compare(found, invoiced, ordered, DiffKind.INVOICED_MORE_THAN_ORDERED,
                            DiffKind.INVOICED_LESS_THAN_ORDERED);
                }
                compare(found, received, invoiced, DiffKind.RECEIVED_MORE_THAN_INVOICED,
                        DiffKind.RECEIVED_LESS_THAN_INVOICED);
            }
            BigDecimal priceDiff = getPriceDiff();
            if (priceDiff != null) {
                found.add(new Diff(priceDiff.signum() > 0 ? DiffKind.PRICE_ABOVE_QUOTE : DiffKind.PRICE_BELOW_QUOTE,
                        priceDiff.abs()));
            }
            return found;
        }

        private static void compare(List<Diff> found, BigDecimal a, BigDecimal b, DiffKind more, DiffKind less) {
            int c = a.compareTo(b);
            if (c != 0) {
                found.add(new Diff(c > 0 ? more : less, plain(a.subtract(b).abs())));
            }
        }

        // The money a difference is about: what was invoiced and did not arrive (a credit to claim; negative when more
        // arrived than invoiced), else what was invoiced against the order, a line not ordered at its amount, plus a
        // price above (or below) the quote on the quantity invoiced. Null when nothing can be priced.
        private BigDecimal stake() {
            if (diffs.isEmpty()) {
                return null;
            }
            BigDecimal value = null;
            boolean has = false;
            for (Diff diff : diffs) {
                switch (diff.kind()) {
                    case RECEIVED_LESS_THAN_INVOICED, RECEIVED_MORE_THAN_INVOICED -> {
                        if (pricePerUnit != null) {
                            value = add(value, invoiced.subtract(getReceived()).multiply(pricePerUnit));
                            has = true;
                        }
                    }
                    default -> {
                    }
                }
            }
            if (!has) {
                for (Diff diff : diffs) {
                    if ((diff.kind() == DiffKind.INVOICED_MORE_THAN_ORDERED
                            || diff.kind() == DiffKind.INVOICED_LESS_THAN_ORDERED) && pricePerUnit != null) {
                        value = add(value, invoiced.subtract(orderLine.getQuantity()).multiply(pricePerUnit));
                    } else if (diff.kind() == DiffKind.NOT_ORDERED) {
                        value = add(value, amount != null ? amount
                                : invoiced != null && pricePerUnit != null ? invoiced.multiply(pricePerUnit) : null);
                    }
                }
            }
            BigDecimal priceDiff = getPriceDiff();
            if (priceDiff != null && invoiced != null) {
                value = add(value, priceDiff.multiply(invoiced));
            }
            return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
        }

        public String getKey() { return key; }
        public Item getItem() { return item; }
        public ShipOrderLine getOrderLine() { return orderLine; }
        public List<DeliveryInvoiceLine> getInvoiceLines() { return invoiceLines; }
        public List<Diff> getDiffs() { return diffs; }
        public InvoiceDecision getDecision() { return decision; }

        /** The item's name, or the invoice's description for a line linked to no item. */
        public String getName() {
            return item != null ? item.getName() : invoiceLines.getFirst().getDescription();
        }

        /** The item's unit, or the invoice's for a line linked to no item. */
        public String getUnit() {
            return item != null ? item.getUnit() : invoiceLines.getFirst().getUnit();
        }

        public ItemCategory getCategory() {
            return orderLine == null ? null : item.getCategory();
        }

        /** Ordered, in the item's unit; null for an invoice line linked to no order line. */
        public BigDecimal getOrdered() {
            return orderLine == null ? null : orderLine.getQuantity();
        }

        /** Received on board (0 when nothing arrived); null for an invoice line linked to no order line. */
        public BigDecimal getReceived() {
            return orderLine == null ? null
                    : orderLine.getReceivedQuantity() == null ? BigDecimal.ZERO : orderLine.getReceivedQuantity();
        }

        /** Invoiced, in the item's unit (the invoice's for a line linked to no item); null: not on it, or not read. */
        public BigDecimal getInvoiced() { return invoiced; }

        public boolean isOnInvoice() {
            return !invoiceLines.isEmpty();
        }

        /** The invoice's unit price (of its own unit), when its lines agree on one. */
        public BigDecimal getUnitPrice() { return unitPrice; }

        /** The invoice's amount for this row: its lines' amounts added up (null when one was not read). */
        public BigDecimal getAmount() { return amount; }

        /** The invoice's price per item unit. */
        public BigDecimal getPricePerUnit() { return pricePerUnit; }

        /** The awarded quote's price per item unit, or null. */
        public BigDecimal getQuoted() { return quoted; }

        /** The invoice's price per item unit minus the quote's, when more than 1% apart; null otherwise. */
        public BigDecimal getPriceDiff() {
            if (pricePerUnit == null || quoted == null || quoted.signum() <= 0 || orderLine == null) {
                return null;
            }
            BigDecimal diff = pricePerUnit.subtract(quoted);
            return diff.abs().compareTo(quoted.multiply(PRICE_TOLERANCE)) > 0 ? diff.setScale(4, RoundingMode.HALF_UP)
                    .stripTrailingZeros() : null;
        }

        /** The money at stake (see stake()), never negative: the words say which way. */
        public BigDecimal getStake() {
            return stake == null ? null : stake.abs();
        }

        /**
         * The invoice's own quantity and unit when it counts in another unit than the item's: "12 bags × 10 kg", or
         * "500 g"; null when it counts in the item's unit, or its lines do not share one.
         */
        public String getInvoiceText() {
            if (invoiceLines.isEmpty() || item == null) {
                return null;
            }
            DeliveryInvoiceLine first = invoiceLines.getFirst();
            if (first.getPackUnitOne() == null) {
                return null;
            }
            BigDecimal quantity = sum(invoiceLines.stream().map(DeliveryInvoiceLine::getQuantity).toList());
            boolean same = invoiceLines.stream().allMatch(l -> Objects.equals(l.getPackUnitOne(), first.getPackUnitOne())
                    && Objects.equals(l.getPack(), first.getPack()));
            if (quantity == null || !same) {
                return null;
            }
            String unit = quantity.compareTo(BigDecimal.ONE) == 0 ? first.getPackUnitOne() : first.getPackUnitMany();
            return quantity.toPlainString() + " " + unit + (first.getPack() == null ? "" : " × " + first.getPack());
        }

        /** The Chief cook's note on the line. */
        public String getCookNote() {
            return orderLine == null ? invoiceLines.getFirst().getCountNote() : orderLine.getCountNote();
        }

        /** The Master's note on the receipt, when it is not the cook's. */
        public String getMasterNote() {
            if (orderLine == null || orderLine.getReceiptNote() == null
                    || orderLine.getReceiptNote().equals(orderLine.getCountNote())) {
                return null;
            }
            return orderLine.getReceiptNote();
        }

        public boolean isDifferent() {
            return !diffs.isEmpty();
        }

        /** What the difference is, as a decision keeps it: a decision holds only while this stays the same. */
        public String getDetail() {
            String text = diffs.stream().map(Diff::detail).collect(Collectors.joining(";"));
            return text.length() > InvoiceDecision.DETAIL_MAX_LENGTH ? text.substring(0, InvoiceDecision.DETAIL_MAX_LENGTH)
                    : text;
        }

        /** The office accepted the difference as it is now. */
        public boolean isAccepted() {
            return decision != null && decision.getKind() == InvoiceDecisionKind.ACCEPTED
                    && getDetail().equals(decision.getDetail());
        }

        /** The decision key of this row's difference. */
        public String getDecisionKey() {
            return "diff:" + key;
        }
    }

    private final ShipOrder order;
    private final DeliveryInvoice invoice;
    private final List<DeliveryInvoiceLine> invoiceLines;
    private final List<Row> rows;
    private final List<ReviewItem> review;
    private final String currency;

    private InvoiceComparison(ShipOrder order, DeliveryInvoice invoice, List<DeliveryInvoiceLine> invoiceLines,
                              List<Row> rows, List<ReviewItem> review, String currency) {
        this.order = order;
        this.invoice = invoice;
        this.invoiceLines = invoiceLines;
        this.rows = rows;
        this.review = review;
        this.currency = currency;
    }

    /** True when the order can be compared: received, with an invoice read into lines. */
    public static boolean applies(ShipOrder order, DeliveryInvoice invoice, List<DeliveryInvoiceLine> invoiceLines) {
        return order.getStatus() == ShipOrderStatus.CLOSED && CountSheet.read(invoice) && !invoiceLines.isEmpty();
    }

    /**
     * The comparison of a received order. quoted: the awarded quote's price per item unit by item id (empty without
     * an award); currency: the quote's (null: none); decisions: the office's, by key.
     */
    public static InvoiceComparison of(ShipOrder order, DeliveryInvoice invoice, List<DeliveryInvoiceLine> invoiceLines,
                                       Map<Long, BigDecimal> quoted, String currency,
                                       Map<String, InvoiceDecision> decisions) {
        List<Row> rows = new ArrayList<>();
        for (ShipOrderLine line : order.getLines()) {
            if (line.isRemoved()) {
                continue;
            }
            List<DeliveryInvoiceLine> linked = invoiceLines.stream()
                    .filter(l -> l.getMatch() != null && l.getMatch().linked() && l.getOrderLine() != null
                            && l.getOrderLine().getId().equals(line.getId()))
                    .toList();
            String key = "o" + line.getId();
            rows.add(new Row(key, line.getItem(), line, linked, quoted.get(line.getItem().getId()),
                    decisions.get("diff:" + key)));
        }
        for (DeliveryInvoiceLine l : invoiceLines) {
            if (l.getOrderLine() == null || l.getMatch() == null || !l.getMatch().linked()) {
                String key = "i" + l.getId();
                Item item = l.getMatch() != null && l.getMatch().linked() ? l.getItem() : null;
                rows.add(new Row(key, item, null, List.of(l), null, decisions.get("diff:" + key)));
            }
        }
        rows.sort(Comparator.comparing((Row row) -> row.getCategory() == null ? Integer.MAX_VALUE
                        : row.getCategory().ordinal())
                .thenComparing(Row::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(Row::getKey));
        return new InvoiceComparison(order, invoice, invoiceLines, List.copyOf(rows),
                review(invoice, invoiceLines, decisions), currency);
    }

    private static List<ReviewItem> review(DeliveryInvoice invoice, List<DeliveryInvoiceLine> lines,
                                           Map<String, InvoiceDecision> decisions) {
        List<ReviewItem> items = new ArrayList<>();
        if (invoice.getCheckNotes() != null) {
            for (String note : invoice.getCheckNotes().split("\n")) {
                if (!note.isBlank()) {
                    String key = checkKey(note.strip());
                    items.add(new ReviewItem(key, ReviewKind.CHECK, note.strip(), null, decisions.get(key)));
                }
            }
        }
        if (invoice.getReferenceWarning() != null && !invoice.getReferenceWarning().isBlank()) {
            items.add(new ReviewItem("ref", ReviewKind.REFERENCE, invoice.getReferenceWarning(), null,
                    decisions.get("ref")));
        }
        for (DeliveryInvoiceLine line : lines) {
            if (line.isFlagged()) {
                String key = "line:" + line.getId();
                items.add(new ReviewItem(key, ReviewKind.LINE, null, line, decisions.get(key)));
            }
        }
        for (DeliveryInvoiceLine line : lines) {
            String key = "match:" + line.getId();
            // To review, or reviewed already (kept, with the decision).
            if ((line.getMatch() == InvoiceMatch.CHECK && line.getItem() != null) || decisions.containsKey(key)) {
                items.add(new ReviewItem(key, ReviewKind.MATCH, null, line, decisions.get(key)));
            }
        }
        for (DeliveryInvoiceLine line : lines) {
            String key = "conv:" + line.getId();
            if (line.getItem() != null && line.getPackFactor() != null && line.getMatch() != null
                    && line.getMatch().linked() && (line.isConversionToReview() || decisions.containsKey(key))) {
                items.add(new ReviewItem(key, ReviewKind.CONVERSION, null, line, decisions.get(key)));
            }
        }
        return List.copyOf(items);
    }

    /** The decision key of a failed check: its text (as long as a key may be). */
    static String checkKey(String note) {
        String key = "check:" + note;
        return key.length() > InvoiceDecision.KEY_MAX_LENGTH ? key.substring(0, InvoiceDecision.KEY_MAX_LENGTH) : key;
    }

    // ---- Reading ------------------------------------------------------------------------------------------------------

    public ShipOrder getOrder() { return order; }
    public DeliveryInvoice getInvoice() { return invoice; }
    public List<DeliveryInvoiceLine> getInvoiceLines() { return invoiceLines; }
    public List<Row> getRows() { return rows; }
    public List<ReviewItem> getReview() { return review; }
    public String getCurrency() { return currency; }

    public Row row(String key) {
        return rows.stream().filter(row -> row.getKey().equals(key)).findFirst().orElse(null);
    }

    public ReviewItem reviewItem(String key) {
        return review.stream().filter(item -> item.key().equals(key)).findFirst().orElse(null);
    }

    public List<Row> getDifferent() {
        return rows.stream().filter(Row::isDifferent).toList();
    }

    public List<Row> getAgreeing() {
        return rows.stream().filter(row -> !row.isDifferent()).toList();
    }

    public int getTotal() {
        return rows.size();
    }

    public int getDifferentTotal() {
        return (int) rows.stream().filter(Row::isDifferent).count();
    }

    /** The rows by category (Provisions, Personal items, then the lines not ordered): lines and how many differ. */
    public List<CategorySummary> getCategories() {
        Map<ItemCategory, int[]> counts = new EnumMap<>(ItemCategory.class);
        int[] notOrdered = new int[2];
        for (Row row : rows) {
            int[] count = row.getCategory() == null ? notOrdered : counts.computeIfAbsent(row.getCategory(), c -> new int[2]);
            count[0]++;
            count[1] += row.isDifferent() ? 1 : 0;
        }
        List<CategorySummary> summary = new ArrayList<>();
        counts.forEach((category, count) -> summary.add(new CategorySummary(category, count[0], count[1])));
        if (notOrdered[0] > 0) {
            summary.add(new CategorySummary(null, notOrdered[0], notOrdered[1]));
        }
        return summary;
    }

    /** The open points to review (neither Correct nor changed). */
    public List<ReviewItem> getReviewOpen() {
        return review.stream().filter(item -> !item.resolved()).toList();
    }

    /** What is left before the order can be marked matched: differences not accepted and points not reviewed. */
    public int getOpenTotal() {
        return (int) rows.stream().filter(row -> row.isDifferent() && !row.isAccepted()).count() + getReviewOpen().size();
    }

    public boolean isMatched() {
        return order.getInvoiceStatus() == InvoiceMatchStatus.MATCHED;
    }

    /** The office may mark it matched: nothing left to decide. */
    public boolean isCanMatch() {
        return order.isInvoiceToMatch() && getOpenTotal() == 0;
    }

    /** What was ordered at the quoted prices, the lines whose price is known; null without a quote. */
    public BigDecimal getOrderedValue() {
        BigDecimal total = null;
        for (Row row : rows) {
            if (row.getOrdered() != null && row.getQuoted() != null) {
                total = add(total, row.getOrdered().multiply(row.getQuoted()));
            }
        }
        return total == null ? null : total.setScale(2, RoundingMode.HALF_UP);
    }

    /** The invoice's total, or its lines' amounts added up when it has none. */
    public BigDecimal getInvoicedTotal() {
        if (invoice.getTotal() != null) {
            return invoice.getTotal();
        }
        BigDecimal total = null;
        for (DeliveryInvoiceLine line : invoiceLines) {
            total = add(total, line.getAmount());
        }
        return total;
    }

    /** The money at stake in the differences, added up. */
    public BigDecimal getDifferencesValue() {
        BigDecimal total = null;
        for (Row row : rows) {
            total = add(total, row.getStake());
        }
        return total == null ? BigDecimal.ZERO.setScale(2) : total;
    }

    /** The invoice's pages with lines, and their lines (the invoice page). */
    public Map<Integer, List<DeliveryInvoiceLine>> linesByPage() {
        Map<Integer, List<DeliveryInvoiceLine>> pages = new LinkedHashMap<>();
        for (DeliveryInvoiceLine line : invoiceLines) {
            pages.computeIfAbsent(line.getPage(), page -> new ArrayList<>()).add(line);
        }
        return pages;
    }

    // ---- Helpers ------------------------------------------------------------------------------------------------------

    private static BigDecimal add(BigDecimal a, BigDecimal b) {
        return a == null ? b : b == null ? a : a.add(b);
    }

    // The sum, or null when any is missing.
    static BigDecimal sum(List<BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            if (value == null) {
                return null;
            }
            total = total.add(value);
        }
        return plain(total);
    }

    static BigDecimal plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
