package com.nereonix.nereonet_mvp.core.rfq;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.nereonix.nereonet_mvp.core.item.ItemCategory;

/**
 * The sealed comparison of an RFQ's quotes (P44), for the office only. Quotes are compared only within one currency
 * (never converted): each currency with two or more quotes gets a table (the currency with most quotes first), items
 * as rows grouped by category (Provisions, then Personal items), suppliers as columns, each cell the price per item
 * unit (so a 5 L can and 1 L bottles compare per litre), the quantity offered and the line total; then subtotal,
 * delivery cost, grand total and delivery date per supplier. A line not offered is "Not offered", never zero. The
 * cheapest complete quote of a table (every line offered in at least the quantity asked) is marked; with a tie,
 * each of them. A quote alone in its currency is listed apart, not compared.
 */
public final class RfqComparison {

    /** Quotes needed for a comparison: of the whole RFQ, and of one currency for its table. */
    public static final int MIN_QUOTES = 2;

    /** One supplier's answer for one line; line is null when it was not offered. */
    public record Cell(RfqQuoteLine line, boolean lowest) {

        public boolean offered() {
            return line != null;
        }
    }

    /** One RFQ line across the suppliers of a table, in the columns' order. */
    public record Row(RfqLine line, List<Cell> cells) {
    }

    /** One category's rows, by item name. */
    public record Group(ItemCategory category, List<Row> rows) {
    }

    /** One supplier's column: its quote, its totals, whether it is complete and the cheapest complete one. */
    public record Column(RfqQuote quote, BigDecimal subtotal, BigDecimal deliveryCost, BigDecimal total,
                         long offered, boolean complete, boolean cheapest) {
    }

    /** The quotes in one currency. */
    public record Table(String currency, List<Column> columns, List<Group> groups) {
    }

    private final List<Table> tables;
    private final List<Column> apart;
    private final int quoted;

    private RfqComparison(List<Table> tables, List<Column> apart, int quoted) {
        this.tables = tables;
        this.apart = apart;
        this.quoted = quoted;
    }

    /** The comparison of the RFQ's QUOTED quotes. */
    public static RfqComparison of(Rfq rfq) {
        List<RfqQuote> quotes = rfq.getQuotes().stream().filter(RfqQuote::isQuoted).toList();
        Map<String, List<RfqQuote>> byCurrency = new LinkedHashMap<>();
        for (RfqQuote quote : quotes) {
            byCurrency.computeIfAbsent(quote.getCurrency(), c -> new ArrayList<>()).add(quote);
        }
        List<Map.Entry<String, List<RfqQuote>>> currencies = new ArrayList<>(byCurrency.entrySet());
        currencies.sort(Comparator.comparing((Map.Entry<String, List<RfqQuote>> e) -> -e.getValue().size())
                .thenComparing(Map.Entry::getKey));

        List<RfqLine> lines = RfqWorkbook.byCategoryAndName(rfq.getLines());
        List<Table> tables = new ArrayList<>();
        List<Column> apart = new ArrayList<>();
        for (Map.Entry<String, List<RfqQuote>> entry : currencies) {
            List<Column> columns = columns(entry.getValue(), lines.size());
            if (columns.size() < MIN_QUOTES) {
                apart.addAll(columns);
                continue;
            }
            Map<ItemCategory, List<Row>> rows = new EnumMap<>(ItemCategory.class);
            for (RfqLine line : lines) {
                List<RfqQuoteLine> offered = columns.stream()
                        .map(c -> c.quote().lineFor(line).orElse(null)).toList();
                BigDecimal lowest = offered.stream().filter(l -> l != null && l.pricePerItemUnit() != null)
                        .map(RfqQuoteLine::pricePerItemUnit).min(Comparator.naturalOrder()).orElse(null);
                long offeredCount = offered.stream().filter(l -> l != null).count();
                List<Cell> cells = offered.stream().map(l -> new Cell(l, l != null && offeredCount > 1
                        && lowest != null && lowest.compareTo(l.pricePerItemUnit()) == 0)).toList();
                rows.computeIfAbsent(line.getCategory(), c -> new ArrayList<>()).add(new Row(line, cells));
            }
            tables.add(new Table(entry.getKey(), columns, rows.entrySet().stream()
                    .map(e -> new Group(e.getKey(), List.copyOf(e.getValue()))).toList()));
        }
        return new RfqComparison(tables, apart, quotes.size());
    }

    // The columns of one currency, in the RFQ's supplier order, the cheapest complete one(s) marked.
    private static List<Column> columns(List<RfqQuote> quotes, int lineCount) {
        BigDecimal cheapest = quotes.stream().filter(RfqQuote::complete).map(RfqQuote::grandTotal)
                .min(Comparator.naturalOrder()).orElse(null);
        return quotes.stream().map(q -> new Column(q, q.subtotal(),
                q.getDeliveryCost() == null ? BigDecimal.ZERO : q.getDeliveryCost(), q.grandTotal(),
                q.getLines().size(), q.complete(),
                quotes.size() > 1 && cheapest != null && q.complete() && q.grandTotal().compareTo(cheapest) == 0))
                .toList();
    }

    /** One table per currency with two or more quotes. */
    public List<Table> getTables() {
        return tables;
    }

    /** The quotes alone in their currency: listed, not compared. */
    public List<Column> getApart() {
        return apart;
    }

    /** How many quotes there are. */
    public int getQuoted() {
        return quoted;
    }

    /** Two or more quotes: the RFQ page shows the comparison part. */
    public boolean isShown() {
        return quoted >= MIN_QUOTES;
    }
}
