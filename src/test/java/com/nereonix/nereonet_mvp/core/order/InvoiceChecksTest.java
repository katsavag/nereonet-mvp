package com.nereonix.nereonet_mvp.core.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.nereonix.nereonet_mvp.core.ai.PageReading.ReadLine;
import com.nereonix.nereonet_mvp.core.order.InvoiceChecks.CheckedLine;
import com.nereonix.nereonet_mvp.core.order.InvoiceChecks.PageInput;
import com.nereonix.nereonet_mvp.core.order.InvoiceChecks.Problem;
import com.nereonix.nereonet_mvp.core.order.InvoiceChecks.Result;

/**
 * Every check of an invoice as read. A line: quantity x price = amount (0.01, or 0.5% for weighed goods); one value
 * recovered from two sure ones ("corrected"), a lost decimal separator, never a quantity from a suspicious value, never
 * an invented quantity ("quantity not read"); low confidence is "check". A page: its total, its line numbers, a page
 * hard to read. The invoice: the net amount and net + VAT = total.
 */
class InvoiceChecksTest {

    private static final double MIN = 0.85;

    // Four ordinary lines to compare a suspicious value with (prices around 2, amounts around 40).
    private static List<ReadLine> others() {
        return List.of(line(1, "PCS", "10", "2.00", "20.00"), line(2, "PCS", "20", "2.50", "50.00"),
                line(3, "PCS", "15", "1.80", "27.00"), line(4, "PCS", "30", "1.20", "36.00"));
    }

    private static ReadLine line(Integer no, String unit, String quantity, String price, String amount) {
        return line(no, unit, quantity, price, amount, 0.97);
    }

    private static ReadLine line(Integer no, String unit, String quantity, String price, String amount, Double confidence) {
        return new ReadLine(no, "Item " + no, unit, number(quantity), number(price), number(amount), "| " + no + " |",
                confidence);
    }

    private static BigDecimal number(String text) {
        return text == null ? null : new BigDecimal(text);
    }

    private static CheckedLine check(ReadLine target) {
        List<ReadLine> lines = new ArrayList<>(others());
        lines.add(target);
        Result result = InvoiceChecks.check(List.of(new PageInput(1, true, lines, null)), null, null, null, MIN);
        return result.lines().getLast();
    }

    @Test
    void aLineThatFitsIsFine() {
        CheckedLine checked = check(line(5, "PCS", "24", "0.90", "21.60"));
        assertThat(checked.flagged()).isFalse();
        assertThat(checked.quantity()).isEqualByComparingTo("24");
    }

    @Test
    void weighedGoodsMayBeOffByHalfAPercent() {
        // 12.48 kg x 3.10 = 38.688; the scale's own rounding gives 38.80 (0.3% off): fine for kg, not for pieces.
        assertThat(check(line(5, "kg", "12.48", "3.10", "38.80")).flagged()).isFalse();
        assertThat(check(line(5, "ΚΙΛΑ", "12.48", "3.10", "38.80")).flagged()).isFalse();
        assertThat(check(line(5, "PCS", "12.48", "3.10", "38.80")).check()).isTrue();
        // 1% off is too much even for kg.
        assertThat(check(line(5, "kg", "12.48", "3.10", "39.07")).check()).isTrue();
    }

    @Test
    void oneUnreadableValueIsRecoveredFromTheOtherTwo() {
        CheckedLine amount = check(line(5, "PCS", "24", "0.90", null));
        assertThat(amount.corrected()).isTrue();
        assertThat(amount.amount()).isEqualByComparingTo("21.60");

        CheckedLine price = check(line(5, "PCS", "24", null, "21.60"));
        assertThat(price.corrected()).isTrue();
        assertThat(price.unitPrice()).isEqualByComparingTo("0.90");

        CheckedLine quantity = check(line(5, "kg", null, "1.85", "23.13"));
        assertThat(quantity.corrected()).isTrue();
        assertThat(quantity.quantity()).isEqualByComparingTo("12.5");
        CheckedLine clean = check(line(5, "PCS", null, "2.00", "48.00"));
        assertThat(clean.corrected()).isTrue();
        assertThat(clean.quantity()).isEqualByComparingTo("24");
        assertThat(clean.quantityNotRead()).isFalse();
    }

    @Test
    void aLostDecimalSeparatorIsRecovered() {
        // "46,25" read as 4625: the amount from quantity x price.
        CheckedLine amount = check(line(5, "kg", "25", "1.85", "4625"));
        assertThat(amount.corrected()).isTrue();
        assertThat(amount.amount()).isEqualByComparingTo("46.25");
        // "1,85" read as 185, next to prices around 2: the price.
        CheckedLine price = check(line(5, "kg", "25", "185", "46.25"));
        assertThat(price.corrected()).isTrue();
        assertThat(price.unitPrice()).isEqualByComparingTo("1.85");
        assertThat(price.quantity()).isEqualByComparingTo("25");
        // "12,500" kg read as 12500, price and amount ordinary: the quantity.
        CheckedLine quantity = check(line(5, "kg", "12500", "1.85", "23.13"));
        assertThat(quantity.corrected()).isTrue();
        assertThat(quantity.quantity()).isEqualByComparingTo("12.5");
    }

    @Test
    void neverAQuantityFromAValueThatMayHaveLostItsSeparator() {
        // The amount 2500 stands out next to amounts around 40 (its separator may be lost): no quantity from it.
        CheckedLine checked = check(line(5, "PCS", null, "2.00", "2500"));
        assertThat(checked.quantityNotRead()).isTrue();
        assertThat(checked.quantity()).isNull();
        assertThat(checked.corrected()).isFalse();
        // The price 250 stands out next to prices around 2.
        assertThat(check(line(5, "PCS", null, "250", "50.00")).quantityNotRead()).isTrue();
        // Not enough other lines to tell: no quantity either.
        Result alone = InvoiceChecks.check(List.of(new PageInput(1, true,
                List.of(line(1, "PCS", null, "2.00", "48.00")), null)), null, null, null, MIN);
        assertThat(alone.lines().getFirst().quantityNotRead()).isTrue();
    }

    @Test
    void neverAnInventedQuantity() {
        // amount / price is not a clean quantity (more than 3 decimals): not read.
        assertThat(check(line(5, "PCS", null, "3.00", "10.00")).quantityNotRead()).isTrue();
        // Two values unreadable: the quantity is not read, nothing recovered.
        CheckedLine two = check(line(5, "PCS", null, null, "48.00"));
        assertThat(two.quantityNotRead()).isTrue();
        assertThat(two.unitPrice()).isNull();
        // The quantity read, price and amount not: "check", the quantity kept.
        CheckedLine kept = check(line(5, "PCS", "24", null, null));
        assertThat(kept.check()).isTrue();
        assertThat(kept.quantity()).isEqualByComparingTo("24");
    }

    @Test
    void valuesThatDoNotFitAndCannotBeExplainedAreChecked() {
        CheckedLine checked = check(line(5, "PCS", "8", "2.00", "6.00"));
        assertThat(checked.check()).isTrue();
        assertThat(checked.corrected()).isFalse();
        assertThat(checked.quantity()).isEqualByComparingTo("8");
    }

    @Test
    void lowConfidenceIsCheckedAndRecoversNothing() {
        assertThat(check(line(5, "PCS", "24", "0.90", "21.60", 0.6)).check()).isTrue();
        CheckedLine unsure = check(line(5, "PCS", null, "2.00", "48.00", 0.6));
        assertThat(unsure.quantityNotRead()).isTrue();
        assertThat(unsure.corrected()).isFalse();
        // No confidence given: not low.
        assertThat(check(line(5, "PCS", "24", "0.90", "21.60", null)).flagged()).isFalse();
    }

    @Test
    void thePageTotalAndTheLineNumbers() {
        List<ReadLine> lines = List.of(line(1, "PCS", "10", "2.00", "20.00"), line(2, "PCS", "20", "2.50", "50.00"),
                line(4, "PCS", "30", "1.20", "36.00"));
        Result result = InvoiceChecks.check(List.of(new PageInput(1, true, lines, new BigDecimal("1773.70"))),
                null, null, null, MIN);
        assertThat(result.problems()).extracting(Problem::text).containsExactly(
                "Page 1: the lines add up to 106.00, the page says 1,773.70",
                "Page 1: line 3 is missing");
        assertThat(result.pagesToCheck()).containsExactly(1);
        // Fewer lines than the numbers suggest: hard to read.
        assertThat(result.hardToRead()).containsExactly(1);

        Result fine = InvoiceChecks.check(List.of(new PageInput(1, true, others(), new BigDecimal("133.00"))),
                null, null, null, MIN);
        assertThat(fine.problems()).isEmpty();
        assertThat(fine.pagesToCheck()).isEmpty();
        assertThat(fine.hardToRead()).isEmpty();
    }

    @Test
    void lineNumbersGoOnFromThePageBefore() {
        Result result = InvoiceChecks.check(List.of(
                new PageInput(1, true, List.of(line(1, "PCS", "1", "1.00", "1.00"), line(2, "PCS", "1", "1.00", "1.00")), null),
                new PageInput(2, true, List.of(line(5, "PCS", "1", "1.00", "1.00")), null)), null, null, null, MIN);
        assertThat(result.problems()).extracting(Problem::text).containsExactly("Page 2: lines 3 and 4 are missing");
        assertThat(result.hardToRead()).containsExactly(2);
    }

    @Test
    void aPageWithoutATableIsHardToRead() {
        Result result = InvoiceChecks.check(List.of(new PageInput(1, true, others(), null),
                new PageInput(2, false, List.of(), null)), null, null, null, MIN);
        assertThat(result.hardToRead()).containsExactly(2);
        assertThat(result.lines()).hasSize(4);
    }

    @Test
    void theNetAmountAndTheTotal() {
        PageInput page = new PageInput(1, true, others(), null);
        assertThat(InvoiceChecks.check(List.of(page), new BigDecimal("133.00"), new BigDecimal("17.29"),
                new BigDecimal("150.29"), MIN).problems()).isEmpty();
        assertThat(InvoiceChecks.check(List.of(page), new BigDecimal("140.00"), new BigDecimal("18.20"),
                new BigDecimal("160.00"), MIN).problems()).extracting(Problem::text).containsExactly(
                "The lines add up to 133.00, the invoice's net amount is 140.00",
                "Net 140.00 + VAT 18.20 = 158.20, the invoice's total is 160.00");
    }
}
