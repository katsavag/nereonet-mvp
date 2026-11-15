package com.nereonix.nereonet_mvp.core.order;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import com.nereonix.nereonet_mvp.core.ai.PageReading.ReadLine;

/**
 * The checks of an invoice as read, plain Java. Nothing is ever invented: a value is recovered only when the
 * other two of its line are sure and agree, and the line says so ("corrected").
 *
 * Per line (quantity x unit price = amount, within 0.01, or 0.5% of the amount for weighed goods: kg, g, lt, ml):
 *   - All three read and they fit: fine.
 *   - All three read and they do not fit (a lost decimal separator only ever makes a value larger):
 *       the amount is 100 times quantity x price: the amount is recovered as quantity x price; the price is 100 times
 *       amount / quantity and stands out next to the other prices: the price is recovered; the quantity is 10, 100
 *       or 1000 times amount / price while price and amount are trusted: the quantity is recovered; else a price
 *       100 times too large is recovered all the same. Anything else: "check".
 *   - Exactly one unreadable and the other two sure: the amount is quantity x price, the price amount / quantity (at
 *     most 4 decimals, and only if it gives the amount back), the quantity amount / price (the fewest decimals that
 *     give the amount back: whole for counted goods, at most 3 for weighed ones; and only if price and amount are
 *     trusted). A quantity that cannot be recovered so is "quantity not read".
 *   - Trusted: price and amount not suspicious, with at least 3 other lines to compare them with.
 *   - Two or more unreadable: "quantity not read" when the quantity is one of them, else "check".
 *   - Sure: read, and the line's confidence is at least min-confidence. Suspicious: 50 times or more the median of
 *     the same column over the invoice's other lines (at least 3 of them): a value whose decimal separator may have
 *     been lost, never used to recover a quantity.
 *   - A confidence under min-confidence also flags the line "check".
 * Per page: the lines add up to the page total (when the page shows one); line numbers run without gaps, and on from
 * the page before; a page without a table, or with fewer lines than its line numbers suggest, is hard to read.
 * Over all pages: the lines add up to the net amount, and net + VAT = total (when the invoice shows them).
 * Every failed check is a Problem in English ("Page 1: the lines add up to 1,759.50, the page says 1,773.70") and
 * marks its page "check the lines".
 */
public final class InvoiceChecks {

    static final BigDecimal CENT = new BigDecimal("0.01");
    static final BigDecimal WEIGHED_TOLERANCE = new BigDecimal("0.005");
    static final BigDecimal SUSPICIOUS = BigDecimal.valueOf(50);
    private static final Set<String> WEIGHED = Set.of("kg", "kgs", "kgr", "κιλα", "κγ", "κιλ", "g", "gr", "γρ",
            "lt", "ltr", "l", "lit", "litre", "liter", "λιτ", "λιτρα", "ml");

    private InvoiceChecks() {
    }

    /** One page as read: its number on board, whether a table was found, its lines and its total. */
    public record PageInput(int page, boolean tableFound, List<ReadLine> lines, BigDecimal pageTotal) {
    }

    /** A line after the checks: its values (recovered ones included) and its flags. */
    public record CheckedLine(int page, ReadLine read, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount,
                              boolean corrected, boolean check, boolean quantityNotRead) {

        public boolean flagged() {
            return corrected || check || quantityNotRead;
        }
    }

    /** A failed check; page null for the invoice as a whole. */
    public record Problem(Integer page, String text) {
    }

    public record Result(List<CheckedLine> lines, List<Problem> problems, Set<Integer> pagesToCheck,
                         Set<Integer> hardToRead) {

        public long flagged() {
            return lines.stream().filter(CheckedLine::flagged).count();
        }
    }

    public static Result check(List<PageInput> pages, BigDecimal net, BigDecimal vat, BigDecimal total,
                               double minConfidence) {
        List<ReadLine> all = pages.stream().flatMap(p -> p.lines().stream()).toList();
        List<CheckedLine> lines = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        Set<Integer> toCheck = new TreeSet<>();
        Set<Integer> hard = new TreeSet<>();
        Integer lastNo = null;
        for (PageInput page : pages) {
            if (!page.tableFound()) {
                hard.add(page.page());
                continue;
            }
            BigDecimal sum = BigDecimal.ZERO;
            boolean complete = true;
            for (ReadLine read : page.lines()) {
                CheckedLine line = line(page.page(), read, all, minConfidence);
                lines.add(line);
                if (line.amount() == null) {
                    complete = false;
                } else {
                    sum = sum.add(line.amount());
                }
            }
            if (page.pageTotal() != null && (!complete || !near(sum, page.pageTotal(), CENT))) {
                problems.add(new Problem(page.page(), complete
                        ? "Page " + page.page() + ": the lines add up to " + money(sum) + ", the page says "
                                + money(page.pageTotal())
                        : "Page " + page.page() + ": an amount was not read, the page says " + money(page.pageTotal())));
                toCheck.add(page.page());
            }
            // Line numbers: without gaps on the page, and on from the page before.
            List<Integer> numbers = page.lines().stream().map(ReadLine::no).filter(n -> n != null).toList();
            List<Integer> missing = new ArrayList<>();
            for (int i = 1; i < numbers.size(); i++) {
                for (int n = numbers.get(i - 1) + 1; n < numbers.get(i) && missing.size() < 50; n++) {
                    missing.add(n);
                }
            }
            if (!numbers.isEmpty() && lastNo != null && numbers.getFirst() > lastNo + 1) {
                for (int n = lastNo + 1; n < numbers.getFirst() && missing.size() < 50; n++) {
                    missing.add(n);
                }
            }
            if (!missing.isEmpty()) {
                Collections.sort(missing);
                problems.add(new Problem(page.page(), "Page " + page.page() + ": "
                        + (missing.size() == 1 ? "line " + missing.getFirst() + " is" : "lines " + list(missing) + " are")
                        + " missing"));
                toCheck.add(page.page());
                hard.add(page.page());
            }
            if (!numbers.isEmpty()) {
                lastNo = numbers.getLast();
            }
            if (page.lines().isEmpty()) {
                hard.add(page.page());
            }
        }
        if (net != null && !lines.isEmpty()) {
            boolean complete = lines.stream().allMatch(l -> l.amount() != null);
            BigDecimal sum = lines.stream().map(CheckedLine::amount).filter(a -> a != null)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (!complete || !near(sum, net, CENT)) {
                problems.add(new Problem(null, "The lines add up to " + money(sum) + ", the invoice's net amount is "
                        + money(net)));
            }
        }
        if (net != null && vat != null && total != null && !near(net.add(vat), total, CENT)) {
            problems.add(new Problem(null, "Net " + money(net) + " + VAT " + money(vat) + " = "
                    + money(net.add(vat)) + ", the invoice's total is " + money(total)));
        }
        return new Result(List.copyOf(lines), List.copyOf(problems), Collections.unmodifiableSet(toCheck),
                Collections.unmodifiableSet(hard));
    }

    static CheckedLine line(int page, ReadLine read, List<ReadLine> all, double minConfidence) {
        BigDecimal q = read.quantity();
        BigDecimal p = read.unitPrice();
        BigDecimal a = read.amount();
        boolean confident = read.confidence() == null || read.confidence() >= minConfidence;
        boolean weighed = weighed(read.unit());
        boolean suspiciousPrice = suspicious(p, read, all, ReadLine::unitPrice);
        boolean suspiciousAmount = suspicious(a, read, all, ReadLine::amount);
        // Price and amount may be trusted to give a quantity: they can be compared with enough other lines and do
        // not stand out.
        boolean trusted = all.size() >= 4 && !suspiciousPrice && !suspiciousAmount;
        int unread = (q == null ? 1 : 0) + (p == null ? 1 : 0) + (a == null ? 1 : 0);

        if (unread == 0) {
            if (fits(q, p, a, weighed)) {
                return new CheckedLine(page, read, q, p, a, false, !confident, false);
            }
            if (confident) {
                BigDecimal product = q.multiply(p);
                // A lost decimal separator makes a value larger, never smaller.
                // The amount's: 100 times quantity x price ("46,25" read as 4625).
                if (near(a, product.movePointRight(2), tolerance(product, weighed).movePointRight(2))) {
                    return new CheckedLine(page, read, q, p, money2(product), true, false, false);
                }
                BigDecimal price = p.movePointLeft(2);
                boolean priceFits = fits(q, price, a, weighed);
                // The price's, when it stands out next to the other lines' prices.
                if (priceFits && suspiciousPrice) {
                    return new CheckedLine(page, read, q, price.stripTrailingZeros(), a, true, false, false);
                }
                // The quantity's (12,500 kg read as 12500), price and amount trustworthy.
                if (trusted && p.signum() > 0) {
                    for (int shift : new int[] {1, 2, 3}) {
                        BigDecimal quantity = plain(q.movePointLeft(shift));
                        if (quantity.scale() <= maxScale(weighed) && fits(quantity, p, a, weighed)) {
                            return new CheckedLine(page, read, quantity, p, a, true, false, false);
                        }
                    }
                }
                if (priceFits) {
                    return new CheckedLine(page, read, q, price.stripTrailingZeros(), a, true, false, false);
                }
            }
            return new CheckedLine(page, read, q, p, a, false, true, false);
        }
        if (unread == 1 && confident) {
            if (a == null) {
                return new CheckedLine(page, read, q, p, money2(q.multiply(p)), true, false, false);
            }
            if (p == null) {
                if (q.signum() != 0) {
                    for (int scale = 2; scale <= 4; scale++) {
                        BigDecimal price = a.divide(q, scale, RoundingMode.HALF_UP);
                        if (fits(q, price, a, weighed)) {
                            return new CheckedLine(page, read, q, price.stripTrailingZeros(), a, true, false, false);
                        }
                    }
                }
                return new CheckedLine(page, read, q, null, a, false, true, false);
            }
            // The quantity: only from a price and an amount that may be trusted, and only a clean one: the fewest
            // decimals that give the amount back (whole for counted goods, at most 3 for weighed ones).
            if (p.signum() > 0 && trusted) {
                BigDecimal exact = a.divide(p, MathContext.DECIMAL64);
                for (int scale = 0; scale <= maxScale(weighed); scale++) {
                    BigDecimal quantity = exact.setScale(scale, RoundingMode.HALF_UP);
                    if (quantity.signum() > 0 && fits(quantity, p, a, weighed)) {
                        return new CheckedLine(page, read, plain(quantity), p, a, true, false, false);
                    }
                }
            }
            return new CheckedLine(page, read, null, p, a, false, false, true);
        }
        return new CheckedLine(page, read, q, p, a, false, q != null, q == null);
    }

    static boolean weighed(String unit) {
        if (unit == null) {
            return false;
        }
        String u = java.text.Normalizer.normalize(unit, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]", "");
        return WEIGHED.contains(u);
    }

    // A recovered quantity has at most 3 decimals for weighed goods, none for counted ones (P60's rule).
    private static int maxScale(boolean weighed) {
        return weighed ? 3 : 0;
    }

    // Without trailing zeros, never in exponent form.
    private static BigDecimal plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    static boolean fits(BigDecimal q, BigDecimal p, BigDecimal a, boolean weighed) {
        return near(q.multiply(p), a, tolerance(a, weighed));
    }

    private static BigDecimal tolerance(BigDecimal amount, boolean weighed) {
        if (!weighed) {
            return CENT;
        }
        BigDecimal half = amount.abs().multiply(WEIGHED_TOLERANCE);
        return half.compareTo(CENT) > 0 ? half : CENT;
    }

    private static boolean near(BigDecimal a, BigDecimal b, BigDecimal tolerance) {
        return a.subtract(b).abs().compareTo(tolerance) <= 0;
    }

    // 50 times or more the median of the column over the other lines (at least 3 values).
    private static boolean suspicious(BigDecimal value, ReadLine self, List<ReadLine> all,
                                      Function<ReadLine, BigDecimal> column) {
        if (value == null) {
            return false;
        }
        List<BigDecimal> others = new ArrayList<>();
        for (ReadLine line : all) {
            if (line != self && column.apply(line) != null && column.apply(line).signum() > 0) {
                others.add(column.apply(line));
            }
        }
        if (others.size() < 3) {
            return false;
        }
        Collections.sort(others);
        BigDecimal median = others.get(others.size() / 2);
        return value.compareTo(median.multiply(SUSPICIOUS)) >= 0;
    }

    private static BigDecimal money2(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** 1759.5 as "1,759.50". */
    static String money(BigDecimal value) {
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH)).format(value);
    }

    private static String list(List<Integer> numbers) {
        List<String> parts = numbers.stream().map(String::valueOf).toList();
        return parts.size() == 2 ? parts.get(0) + " and " + parts.get(1)
                : String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.getLast();
    }
}
