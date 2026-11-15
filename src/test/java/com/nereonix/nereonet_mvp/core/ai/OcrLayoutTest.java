package com.nereonix.nereonet_mvp.core.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.nereonix.nereonet_mvp.core.ai.OnnxOcr.TextBox;
import com.nereonix.nereonet_mvp.core.ai.PageReading.ReadLine;

/**
 * P67: the boxes of text of the local OCR turned into rows, the table and its columns. A straight page; the same
 * page tilted; right-aligned quantities that stick out under the price's header; a description going on to the next
 * row; the page total below the table read from the text; a page without a header; a line's confidence from its
 * numbers only.
 */
class OcrLayoutTest {

    // Header cells: No 100-150, Description 160-400, Unit 600-660, Qty 700-760, Unit price 800-920, Amount 940-1040.
    private static List<TextBox> page(double slope) {
        List<TextBox> boxes = new ArrayList<>();
        add(boxes, slope, 100, 20, 500, "Kestrel Ship Supply S.A.", 0.98);
        add(boxes, slope, 100, 60, 400, "Invoice No: A-4471", 0.98);
        add(boxes, slope, 600, 60, 900, "Page 1 of 2", 0.98);
        int y = 140;
        add(boxes, slope, 100, y, 150, "No", 0.99);
        add(boxes, slope, 160, y, 400, "Description", 0.99);
        add(boxes, slope, 600, y, 660, "Unit", 0.99);
        add(boxes, slope, 700, y, 760, "Qty", 0.99);
        add(boxes, slope, 800, y, 920, "Unit price", 0.99);
        add(boxes, slope, 940, y, 1040, "Amount", 0.99);
        String[][] lines = {
                {"1", "Tomatoes", "KG", "25.000", "1.85", "46.25"},
                {"2", "Potatoes", "KG", "60.000", "0.72", "43.20"},
                {"3", "Eggs, large", "PCS", "360", "0.21", "75.60"},
        };
        for (String[] line : lines) {
            y += 40;
            add(boxes, slope, 100, y, 130, line[0], 0.99);
            add(boxes, slope, 160, y, 160 + 14 * line[1].length(), line[1], 0.6);
            add(boxes, slope, 600, y, 650, line[2], 0.95);
            // Right-aligned, past the right of "Qty": it overlaps "Unit price" more than "Qty".
            add(boxes, slope, 830 - 12 * line[3].length(), y, 830, line[3], line[0].equals("2") ? 0.7 : 0.97);
            add(boxes, slope, 860, y, 920, line[4], 0.98);
            add(boxes, slope, 980, y, 1040, line[5], 0.99);
            if (line[0].equals("3")) {
                y += 40;
                add(boxes, slope, 160, y, 330, "(free range)", 0.9);
            }
        }
        y += 60;
        add(boxes, slope, 700, y, 900, "Page total", 0.98);
        add(boxes, slope, 960, y, 1040, "165.05", 0.98);
        return boxes;
    }

    // A box 30 high at (left, top), turned by the slope about the page's corner, as a tilted photo shows it.
    private static void add(List<TextBox> boxes, double slope, int left, int top, int right, String text,
                            double confidence) {
        double cx = (left + right) / 2.0;
        double cy = top + 15;
        int dx = (int) Math.round(-slope * cy);
        int dy = (int) Math.round(slope * cx);
        boxes.add(new TextBox(left + dx, top + dy, right + dx, top + 30 + dy, text, confidence));
    }

    @Test
    void aStraightPage() {
        PageReading reading = OcrLayout.read(page(0));

        assertThat(reading.tableFound()).isTrue();
        assertThat(reading.supplier()).isEqualTo("Kestrel Ship Supply S.A.");
        assertThat(reading.invoiceNumber()).isEqualTo("A-4471");
        assertThat(reading.pageNumber()).isEqualTo(1);
        assertThat(reading.pageCount()).isEqualTo(2);
        assertThat(reading.pageTotal()).isEqualByComparingTo("165.05");
        assertLines(reading.lines());
    }

    @Test
    void aTiltedPage() {
        for (double slope : new double[]{-0.04, 0.04}) {
            PageReading reading = OcrLayout.read(page(slope));

            assertThat(reading.tableFound()).as("slope %s", slope).isTrue();
            assertThat(reading.pageTotal()).isEqualByComparingTo("165.05");
            assertLines(reading.lines());
        }
    }

    private static void assertLines(List<ReadLine> lines) {
        assertThat(lines).hasSize(3);
        assertThat(lines).extracting(ReadLine::no).containsExactly(1, 2, 3);
        assertThat(lines).extracting(ReadLine::description)
                .containsExactly("Tomatoes", "Potatoes", "Eggs, large (free range)");
        assertThat(lines).extracting(ReadLine::unit).containsExactly("KG", "KG", "PCS");
        assertThat(lines.get(0).quantity()).isEqualByComparingTo("25");
        assertThat(lines.get(2).quantity()).isEqualByComparingTo("360");
        assertThat(lines.get(0).unitPrice()).isEqualByComparingTo("1.85");
        assertThat(lines.get(1).amount()).isEqualByComparingTo("43.20");
        // The numbers' lowest: the description's 0.6 does not count, line 2's quantity (0.7) does.
        assertThat(lines).extracting(ReadLine::confidence).containsExactly(0.97, 0.7, 0.9);
    }

    @Test
    void theSlopeOfATiltedPage() {
        assertThat(OcrLayout.slope(page(0))).isZero();
        assertThat(OcrLayout.slope(page(0.03))).isCloseTo(0.03, org.assertj.core.data.Offset.offset(0.01));
        assertThat(OcrLayout.slope(OcrLayout.straighten(page(0.03))))
                .isCloseTo(0, org.assertj.core.data.Offset.offset(0.005));
    }

    @Test
    void aPageWithoutAHeaderIsText() {
        List<TextBox> boxes = List.of(
                new TextBox(100, 20, 500, 50, "Kestrel Ship Supply S.A.", 0.98),
                new TextBox(100, 60, 400, 90, "Invoice No: A-4471", 0.98),
                new TextBox(100, 100, 300, 130, "Total", 0.98),
                new TextBox(400, 100, 500, 130, "12,50", 0.98));

        PageReading reading = OcrLayout.read(boxes);

        assertThat(reading.tableFound()).isFalse();
        assertThat(reading.lines()).isEmpty();
        assertThat(reading.invoiceNumber()).isEqualTo("A-4471");
        assertThat(reading.total()).isEqualByComparingTo("12.50");
    }

    @Test
    void eachWordInOneAlphabet() {
        // Greek Β in BOX; Latin P, a, o, i, Y, M in Greek words; Greek ο in "frozen".
        assertThat(OcrLayout.oneScript("ΒOX")).isEqualTo("BOX");
        assertThat(OcrLayout.oneScript("Pύζι μπασμάτι")).isEqualTo("Ρύζι μπασμάτι");
        assertThat(OcrLayout.oneScript("Black pepper / Μaύρo πιπέρι")).isEqualTo("Black pepper / Μαύρο πιπέρι");
        assertThat(OcrLayout.oneScript("Ψωμi του τοστ, Yγρό, Mήλα")).isEqualTo("Ψωμι του τοστ, Υγρό, Μήλα");
        assertThat(OcrLayout.oneScript("Chicken fillet, frοzen")).isEqualTo("Chicken fillet, frozen");
        // One alphabet: as it is.
        assertThat(OcrLayout.oneScript("ΤΙΜΟΛΟΓΙΟ KG 25,000")).isEqualTo("ΤΙΜΟΛΟΓΙΟ KG 25,000");
    }

    @Test
    void nothingRead() {
        PageReading reading = OcrLayout.read(List.of());

        assertThat(reading.tableFound()).isFalse();
        assertThat(reading.lines()).isEmpty();
    }
}
