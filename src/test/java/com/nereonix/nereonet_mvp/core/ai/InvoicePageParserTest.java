package com.nereonix.nereonet_mvp.core.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.nereonix.nereonet_mvp.core.ai.InvoicePageParser.Table;
import com.nereonix.nereonet_mvp.core.ai.PageReading.ReadLine;

import tools.jackson.databind.JsonNode;

/**
 * P62: the markdown table parser and the number formats. The sample invoice A-4471 (samples/invoice/) written out as
 * the OCR writes it is read back line for line as its expected JSON says; English and Greek headers; 1,234.56 and
 * 1.234,56; a table given apart with a placeholder; totals in the text and in the table; a description going on to
 * the next row; a cell that is not a number is not read.
 */
class InvoicePageParserTest {

    private final FakeInvoiceReader fake = new FakeInvoiceReader("samples/invoice");

    @Test
    void theSampleInvoiceIsReadAsExpected() {
        JsonNode expected = fake.sample();
        List<ReadLine> all = new ArrayList<>();
        for (int page = 1; page <= 2; page++) {
            PageReading reading = InvoicePageParser.parse(fake.markdown(page), List.of(), 0.9);
            assertThat(reading.tableFound()).isTrue();
            assertThat(reading.supplier()).isEqualTo("Kestrel Ship Supply S.A.");
            assertThat(reading.invoiceNumber()).isEqualTo("A-4471");
            assertThat(reading.date()).isEqualTo(LocalDate.of(2026, 11, 12));
            assertThat(reading.orderReference()).isEqualTo("SR-000052");
            assertThat(reading.pageNumber()).isEqualTo(page);
            assertThat(reading.pageCount()).isEqualTo(2);
            assertThat(reading.pageTotal())
                    .isEqualByComparingTo(expected.path("pages").path(page - 1).path("pageTotal").asString());
            all.addAll(reading.lines());
            if (page == 1) {
                assertThat(reading.lines()).hasSize(22);
                assertThat(reading.net()).isNull();
                assertThat(reading.vat()).isNull();
            } else {
                assertThat(reading.lines()).hasSize(18);
                assertThat(reading.net()).isEqualByComparingTo(expected.path("net").asString());
                assertThat(reading.vat()).isEqualByComparingTo(expected.path("vat").asString());
                assertThat(reading.total()).isEqualByComparingTo(expected.path("total").asString());
            }
        }
        assertThat(all).hasSize(40);
        for (int i = 0; i < 40; i++) {
            JsonNode line = expected.path("lines").path(i);
            ReadLine read = all.get(i);
            assertThat(read.no()).isEqualTo(line.path("no").asInt());
            assertThat(read.description()).isEqualTo(line.path("description").asString());
            assertThat(read.unit()).isEqualTo(line.path("unit").asString());
            assertThat(read.quantity()).as("quantity of line %d", i + 1)
                    .isEqualByComparingTo(line.path("quantity").asString());
            assertThat(read.unitPrice()).isEqualByComparingTo(line.path("unitPrice").asString());
            assertThat(read.amount()).isEqualByComparingTo(line.path("amount").asString());
            assertThat(read.confidence()).isEqualTo(0.9);
            assertThat(read.raw()).startsWith("| " + line.path("no").asInt() + " | ");
        }
    }

    @Test
    void anEnglishInvoiceWithTheTableGivenApart() {
        String markdown = """
                # Harbour Provisions Ltd

                INVOICE NO. INV-2026-118      Date: 2026-11-03
                Customer order ref: PO 4471/B
                Page 1 of 1

                [tbl-0.md](tbl-0.md)

                Subtotal: 1,265.00
                VAT 24%: 303.60
                Total due: EUR 1,568.60
                """;
        String table = """
                | # | Item | UoM | Quantity | Price | Value |
                |---|------|-----|---------:|------:|------:|
                | 1 | Rice, long grain | kg | 500 | 1.25 | 625.00 |
                | 2 | Olive oil | can | 20 | 32.00 | 640.00 |
                |   | Extra virgin, 5 lt |  |  |  |  |
                """;
        PageReading reading = InvoicePageParser.parse(markdown, List.of(new Table(table, 0.81)), 0.99);
        assertThat(reading.tableFound()).isTrue();
        assertThat(reading.supplier()).isEqualTo("Harbour Provisions Ltd");
        assertThat(reading.invoiceNumber()).isEqualTo("INV-2026-118");
        assertThat(reading.date()).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(reading.orderReference()).isEqualTo("PO 4471/B");
        assertThat(reading.pageNumber()).isEqualTo(1);
        assertThat(reading.pageCount()).isEqualTo(1);
        assertThat(reading.net()).isEqualByComparingTo("1265.00");
        assertThat(reading.vat()).isEqualByComparingTo("303.60");
        assertThat(reading.total()).isEqualByComparingTo("1568.60");
        assertThat(reading.lines()).hasSize(2);
        ReadLine rice = reading.lines().get(0);
        assertThat(rice.quantity()).isEqualByComparingTo("500");
        assertThat(rice.unitPrice()).isEqualByComparingTo("1.25");
        assertThat(rice.amount()).isEqualByComparingTo("625.00");
        assertThat(rice.confidence()).isEqualTo(0.81);
        // The row with only a description goes on with the line before.
        assertThat(reading.lines().get(1).description()).isEqualTo("Olive oil Extra virgin, 5 lt");
    }

    @Test
    void greekHeadersTotalsInTheTableAndUnreadableCells() {
        String markdown = """
                Παραγγελία: 12
                | Α/Α | Περιγραφή | Μ.Μ. | Ποσότητα | Τιμή μονάδας | Αξία |
                |---|---|---|---|---|---|
                | 1 | Ντομάτες | ΚΙΛΑ | 12,500 | 1,85 | 23,13 |
                | 2 | Αυγά | ΤΕΜ | 4S | 0,21 | 75,60 |
                | 3 | Γάλα | ΛΙΤ |  | 1,15 | 110,40 |
                |  | Σύνολο σελίδας |  |  |  | 209,13 |
                """;
        PageReading reading = InvoicePageParser.parse(markdown, List.of(), null);
        assertThat(reading.orderReference()).isEqualTo("12");
        assertThat(reading.lines()).hasSize(3);
        // 12,500 next to 1,85: the page writes decimals with a comma.
        assertThat(reading.lines().get(0).quantity()).isEqualByComparingTo("12.5");
        // "4S" is not a number: not read, never 4.
        assertThat(reading.lines().get(1).quantity()).isNull();
        assertThat(reading.lines().get(2).quantity()).isNull();
        assertThat(reading.pageTotal()).isEqualByComparingTo("209.13");
    }

    @Test
    void aPageWithoutATableOfLines() {
        PageReading reading = InvoicePageParser.parse("""
                # Kestrel Ship Supply S.A.
                Thank you for your order.
                | Bank | IBAN |
                |---|---|
                | Fiction Bank | GR00 0000 |
                """, List.of(), 0.9);
        assertThat(reading.tableFound()).isFalse();
        assertThat(reading.lines()).isEmpty();
        assertThat(reading.supplier()).isEqualTo("Kestrel Ship Supply S.A.");
    }

    @Test
    void aVatRegistrationNumberIsNotTheVat() {
        PageReading reading = InvoicePageParser.parse("""
                12 Akti Fiction, Piraeus · VAT no. EL 000000000
                Φ.Π.Α. 13%: 369,02
                """, List.of(), null);
        assertThat(reading.vat()).isEqualByComparingTo("369.02");
    }

    @Test
    void numbersInBothFormats() {
        assertThat(InvoiceNumbers.parseCell("1,234.56", null)).isEqualByComparingTo("1234.56");
        assertThat(InvoiceNumbers.parseCell("1.234,56", null)).isEqualByComparingTo("1234.56");
        assertThat(InvoiceNumbers.parseCell("1.234.567", null)).isEqualByComparingTo("1234567");
        assertThat(InvoiceNumbers.parseCell("1 234,56 €", null)).isEqualByComparingTo("1234.56");
        assertThat(InvoiceNumbers.parseCell("EUR 46.25", null)).isEqualByComparingTo("46.25");
        assertThat(InvoiceNumbers.parseCell("0,750", null)).isEqualByComparingTo("0.75");
        assertThat(InvoiceNumbers.parseCell("25 kg", null)).isEqualByComparingTo("25");
        assertThat(InvoiceNumbers.parseCell("1,85", null)).isEqualByComparingTo("1.85");
        // One separator and three digits: the page's separator decides; without one, ',' groups thousands.
        assertThat(InvoiceNumbers.parseCell("12,500", ',')).isEqualByComparingTo("12.5");
        assertThat(InvoiceNumbers.parseCell("12,500", '.')).isEqualByComparingTo("12500");
        assertThat(InvoiceNumbers.parseCell("12,500", null)).isEqualByComparingTo("12500");
        assertThat(InvoiceNumbers.parseCell("12.500", null)).isEqualByComparingTo("12.5");
        assertThat(InvoiceNumbers.parseCell("12.500", ',')).isEqualByComparingTo("12500");
        // Not numbers.
        assertThat(InvoiceNumbers.parseCell("4S,00", null)).isNull();
        assertThat(InvoiceNumbers.parseCell("", null)).isNull();
        assertThat(InvoiceNumbers.parseCell("1,23,45", null)).isNull();
        assertThat(InvoiceNumbers.parseCell("12.34.5", null)).isNull();
        // The page's separator: what most unambiguous numbers use.
        assertThat(InvoiceNumbers.decimalSeparator(List.of("1,85 0,72 46,25 1.869,50"))).isEqualTo(',');
        assertThat(InvoiceNumbers.decimalSeparator(List.of("1.85 0.72 1,869.50"))).isEqualTo('.');
        assertThat(InvoiceNumbers.decimalSeparator(List.of("25 360 12,500"))).isNull();
        assertThat(InvoiceNumbers.lastNumber("VAT 13%: 369,02", ',')).isEqualByComparingTo(new BigDecimal("369.02"));
        assertThat(InvoiceNumbers.lastNumber("VAT 13%", ',')).isNull();
    }
}
