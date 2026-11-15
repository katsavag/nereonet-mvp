package com.nereonix.nereonet_mvp.core.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.nereonix.nereonet_mvp.core.ai.InvoicePageParser.Column;
import com.nereonix.nereonet_mvp.core.ai.InvoicePageParser.Table;
import com.nereonix.nereonet_mvp.core.ai.OnnxOcr.TextBox;
import com.nereonix.nereonet_mvp.core.ai.PageReading.ReadLine;

/**
 * Turns the boxes of text the local OCR found on a page into the page's text and its table of lines, written as
 * markdown, and reads them with InvoicePageParser (as the fake's pages are read). Plain Java, no model:
 *   - Letters: each word in one alphabet (oneScript), as the Greek model may mix Latin and Greek letters that look
 *     alike.
 *   - Rows: the page may be photographed slightly tilted, so the tilt is measured first (the median slope between
 *     each box and its neighbour on the right) and every box is turned back by it. Boxes whose centres are then
 *     closer than half the usual box height are one row.
 *   - The header: the first row whose boxes name the columns (InvoicePageParser.columns: Description, Qty and a price
 *     or an amount).
 *   - The table: the rows after the header with at least three boxes, and the rows in between whose boxes are all in
 *     the description column (a description going on). The first other row, or a gap of more than three usual rows,
 *     ends it. The columns are the body's boxes grouped where they overlap across rows (numbers are right-aligned
 *     and may stick out of their header), each group under the header it overlaps most.
 *   - Every other row is a line of the page's text, its boxes joined by spaces.
 * A line's confidence is the lowest of its boxes with a digit (over its rows, when its description goes on): the
 * numbers are what the checks compare, a misread letter in the description does not flag the line.
 */
final class OcrLayout {

    private static final double MAX_SLOPE = 0.15;
    private static final int MIN_BODY_BOXES = 3;
    private static final double MAX_GAP_ROWS = 3.0;

    private OcrLayout() {
    }

    /** One row of the page: its boxes from left to right. */
    record Row(List<TextBox> boxes, double y) {

        String text() {
            return String.join(" ", boxes.stream().map(TextBox::text).toList());
        }

        List<String> cells() {
            return boxes.stream().map(TextBox::text).toList();
        }

        // The boxes with a digit hold what the checks compare (number, quantity, price, amount): the lowest of
        // theirs; a row without one, the lowest of all.
        double confidence() {
            return boxes.stream().filter(box -> box.text().chars().anyMatch(Character::isDigit))
                    .mapToDouble(TextBox::confidence).min()
                    .orElse(boxes.stream().mapToDouble(TextBox::confidence).min().orElse(0));
        }
    }

    /** The page as read: text and table go through InvoicePageParser; each line gets its own rows' confidence. */
    static PageReading read(List<TextBox> boxes) {
        List<TextBox> cleaned = boxes.stream().map(box -> new TextBox(box.left(), box.top(), box.right(),
                box.bottom(), oneScript(box.text()), box.confidence())).toList();
        List<Row> rows = rows(straighten(cleaned));
        int headerIndex = -1;
        Column[] headerColumns = null;
        for (int i = 0; i < rows.size() && headerColumns == null; i++) {
            headerColumns = InvoicePageParser.columns(rows.get(i).cells());
            headerIndex = i;
        }
        StringBuilder text = new StringBuilder();
        if (headerColumns == null) {
            rows.forEach(row -> text.append(row.text()).append('\n'));
            return InvoicePageParser.parse(text.toString(), List.of(), null);
        }
        Row header = rows.get(headerIndex);
        List<Row> body = body(rows, headerIndex, header, headerColumns);
        for (int i = 0; i < rows.size(); i++) {
            if (i < headerIndex || i > headerIndex + body.size()) {
                text.append(rows.get(i).text()).append('\n');
            }
        }
        List<int[]> spans = spans(body);
        List<Integer> columnOf = columns(spans, header);
        StringBuilder table = new StringBuilder();
        table.append(markdownRow(header.cells())).append('\n');
        table.append("|").append(" --- |".repeat(header.boxes().size())).append('\n');
        Map<String, Double> confidence = new HashMap<>();
        for (Row row : body) {
            String[] cells = new String[header.boxes().size()];
            for (TextBox box : row.boxes()) {
                int column = columnOf.get(span(spans, box));
                cells[column] = cells[column] == null ? box.text() : cells[column] + " " + box.text();
            }
            List<String> list = new ArrayList<>();
            for (String cell : cells) {
                list.add(cell == null ? "" : cell.replace("|", "/"));
            }
            confidence.put(markdownRow(list), row.confidence());
            table.append(markdownRow(list)).append('\n');
        }
        double tableConfidence = body.stream().mapToDouble(Row::confidence).min().orElse(header.confidence());
        PageReading reading = InvoicePageParser.parse(text.toString(),
                List.of(new Table(table.toString(), tableConfidence)), tableConfidence);
        List<ReadLine> lines = reading.lines().stream()
                .map(line -> line.withConfidence(lineConfidence(line, confidence, tableConfidence)))
                .toList();
        return reading.withLines(lines);
    }

    // The lowest confidence of the rows the line was read from (its raw holds one markdown row per table row).
    private static Double lineConfidence(ReadLine line, Map<String, Double> confidence, double fallback) {
        if (line.raw() == null) {
            return fallback;
        }
        double lowest = Double.MAX_VALUE;
        for (String raw : line.raw().split("\n")) {
            Double value = confidence.get(raw);
            if (value != null) {
                lowest = Math.min(lowest, value);
            }
        }
        return lowest == Double.MAX_VALUE ? fallback : lowest;
    }

    // --- Letters ---------------------------------------------------------------------------------------------------

    // Latin and Greek letters that look the same, in pairs (Latin, Greek).
    private static final String LOOKALIKE_LATIN = "ABEHIKMNOPTXYZoviptxuka";
    private static final String LOOKALIKE_GREEK = "ΑΒΕΗΙΚΜΝΟΡΤΧΥΖονιρτχυκα";

    /**
     * Each word in one alphabet: the Greek model reads "BOX" as "ΒOX" (a Greek Β) or "Ρύζι" as "Pύζι" (a Latin P).
     * In a word with both Latin and Greek letters, the letters that look alike in both are written in the alphabet of
     * the word's other letters (the more of them); when every letter looks alike (ΒOX), in Latin, as units and codes
     * are. A word in one alphabet is left as it is.
     */
    static String oneScript(String text) {
        StringBuilder result = new StringBuilder(text.length());
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || !Character.isLetter(text.charAt(i))) {
                result.append(word(text.substring(start, i)));
                if (i < text.length()) {
                    result.append(text.charAt(i));
                }
                start = i + 1;
            }
        }
        return result.toString();
    }

    private static String word(String word) {
        int latin = 0;
        int greek = 0;
        boolean anyLatin = false;
        boolean anyGreek = false;
        for (char c : word.toCharArray()) {
            boolean isGreek = Character.UnicodeScript.of(c) == Character.UnicodeScript.GREEK;
            boolean isLatin = Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN;
            anyGreek |= isGreek;
            anyLatin |= isLatin;
            if (isGreek && LOOKALIKE_GREEK.indexOf(c) < 0) {
                greek++;
            } else if (isLatin && LOOKALIKE_LATIN.indexOf(c) < 0) {
                latin++;
            }
        }
        if (!anyLatin || !anyGreek) {
            return word;
        }
        boolean toGreek = greek > latin;
        StringBuilder out = new StringBuilder(word.length());
        for (char c : word.toCharArray()) {
            int index = toGreek ? LOOKALIKE_LATIN.indexOf(c) : LOOKALIKE_GREEK.indexOf(c);
            out.append(index < 0 ? c : (toGreek ? LOOKALIKE_GREEK : LOOKALIKE_LATIN).charAt(index));
        }
        return out.toString();
    }

    // --- Rows ------------------------------------------------------------------------------------------------------

    /**
     * The boxes as if the page had been photographed straight: each turned back by the page's tilt (a small angle,
     * so x + slope * y and y - slope * x), so that a column stays one column from the top of the table to its foot.
     */
    static List<TextBox> straighten(List<TextBox> boxes) {
        double slope = slope(boxes);
        if (slope == 0) {
            return boxes;
        }
        return boxes.stream().map(box -> {
            int dx = (int) Math.round(slope * box.centerY());
            int dy = (int) Math.round(slope * box.centerX());
            return new TextBox(box.left() + dx, box.top() - dy, box.right() + dx, box.bottom() - dy, box.text(),
                    box.confidence());
        }).toList();
    }

    static List<Row> rows(List<TextBox> boxes) {
        if (boxes.isEmpty()) {
            return List.of();
        }
        double slope = slope(boxes);
        double tolerance = median(boxes.stream().map(b -> (double) b.height()).toList()) / 2;
        List<TextBox> sorted = new ArrayList<>(boxes);
        sorted.sort(Comparator.comparingDouble(b -> corrected(b, slope)));
        List<Row> rows = new ArrayList<>();
        List<TextBox> current = new ArrayList<>();
        double sum = 0;
        for (TextBox box : sorted) {
            double y = corrected(box, slope);
            if (!current.isEmpty() && y - sum / current.size() > tolerance) {
                rows.add(row(current, sum / current.size()));
                current = new ArrayList<>();
                sum = 0;
            }
            current.add(box);
            sum += y;
        }
        rows.add(row(current, sum / current.size()));
        return rows;
    }

    private static Row row(List<TextBox> boxes, double y) {
        List<TextBox> sorted = new ArrayList<>(boxes);
        sorted.sort(Comparator.comparingInt(TextBox::left));
        return new Row(List.copyOf(sorted), y);
    }

    private static double corrected(TextBox box, double slope) {
        return box.centerY() - slope * box.centerX();
    }

    /** The page's tilt: the median slope from each box to the nearest box on its right in the same line of text. */
    static double slope(List<TextBox> boxes) {
        List<Double> slopes = new ArrayList<>();
        for (TextBox a : boxes) {
            TextBox nearest = null;
            for (TextBox b : boxes) {
                if (b == a || b.left() < a.right() - a.height() / 2
                        || Math.abs(b.centerY() - a.centerY()) > Math.min(a.height(), b.height()) * 0.6) {
                    continue;
                }
                if (nearest == null || b.left() < nearest.left()) {
                    nearest = b;
                }
            }
            if (nearest != null && nearest.centerX() > a.centerX()) {
                slopes.add((nearest.centerY() - a.centerY()) / (nearest.centerX() - a.centerX()));
            }
        }
        if (slopes.isEmpty()) {
            return 0;
        }
        double slope = median(slopes);
        return Math.max(-MAX_SLOPE, Math.min(MAX_SLOPE, slope));
    }

    // --- The table -------------------------------------------------------------------------------------------------

    private static List<Row> body(List<Row> rows, int headerIndex, Row header, Column[] columns) {
        // The description column's right edge, from the header: a row of boxes left of it alone goes on a description.
        int descriptionRight = Integer.MIN_VALUE;
        int nextLeft = Integer.MAX_VALUE;
        for (int i = 0; i < columns.length; i++) {
            if (columns[i] == Column.DESCRIPTION) {
                descriptionRight = header.boxes().get(i).right();
                if (i + 1 < columns.length) {
                    nextLeft = header.boxes().get(i + 1).left();
                }
            }
        }
        List<Row> body = new ArrayList<>();
        List<Double> gaps = new ArrayList<>();
        double previousY = header.y();
        for (int i = headerIndex + 1; i < rows.size(); i++) {
            Row row = rows.get(i);
            double gap = row.y() - previousY;
            if (gaps.size() >= 2 && gap > median(gaps) * MAX_GAP_ROWS) {
                break;
            }
            boolean line = row.boxes().size() >= MIN_BODY_BOXES;
            int limit = nextLeft == Integer.MAX_VALUE ? descriptionRight : nextLeft;
            boolean continues = !body.isEmpty() && descriptionRight != Integer.MIN_VALUE
                    && row.boxes().stream().allMatch(box -> box.right() <= limit
                            && box.left() >= header.boxes().getFirst().left() - box.height());
            if (!line && !continues) {
                break;
            }
            body.add(row);
            gaps.add(gap);
            previousY = row.y();
        }
        return body;
    }

    /** The table's columns: the body's boxes grouped where their left-right spans overlap, from left to right. */
    static List<int[]> spans(List<Row> body) {
        List<int[]> intervals = new ArrayList<>();
        for (Row row : body) {
            if (row.boxes().size() >= MIN_BODY_BOXES) {
                row.boxes().forEach(box -> intervals.add(new int[]{box.left(), box.right()}));
            }
        }
        intervals.sort(Comparator.comparingInt(span -> span[0]));
        List<int[]> spans = new ArrayList<>();
        for (int[] interval : intervals) {
            int[] last = spans.isEmpty() ? null : spans.getLast();
            if (last != null && interval[0] < last[1]) {
                last[1] = Math.max(last[1], interval[1]);
            } else {
                spans.add(new int[]{interval[0], interval[1]});
            }
        }
        return spans;
    }

    // The span a box belongs to: the one it overlaps most, else the nearest.
    private static int span(List<int[]> spans, TextBox box) {
        if (spans.isEmpty()) {
            return 0;
        }
        int best = -1;
        int bestOverlap = 0;
        for (int i = 0; i < spans.size(); i++) {
            int overlap = Math.min(box.right(), spans.get(i)[1]) - Math.max(box.left(), spans.get(i)[0]);
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = i;
            }
        }
        if (best >= 0) {
            return best;
        }
        double nearest = Double.MAX_VALUE;
        for (int i = 0; i < spans.size(); i++) {
            double distance = Math.abs((spans.get(i)[0] + spans.get(i)[1]) / 2.0 - box.centerX());
            if (distance < nearest) {
                nearest = distance;
                best = i;
            }
        }
        return best;
    }

    /**
     * The header cell each column span is under. First one span per header cell, the pairs that overlap most first
     * (a right-aligned quantity may overlap the price's header a little, but the prices overlap it more); a span left
     * over joins the header cell it overlaps most, else the nearest.
     */
    static List<Integer> columns(List<int[]> spans, Row header) {
        int cells = header.boxes().size();
        Integer[] columnOf = new Integer[spans.size()];
        boolean[] taken = new boolean[cells];
        List<int[]> pairs = new ArrayList<>();
        for (int s = 0; s < spans.size(); s++) {
            for (int c = 0; c < cells; c++) {
                int overlap = overlap(spans.get(s), header.boxes().get(c));
                if (overlap > 0) {
                    pairs.add(new int[]{s, c, overlap});
                }
            }
        }
        pairs.sort(Comparator.comparingInt((int[] pair) -> pair[2]).reversed());
        for (int[] pair : pairs) {
            if (columnOf[pair[0]] == null && !taken[pair[1]]) {
                columnOf[pair[0]] = pair[1];
                taken[pair[1]] = true;
            }
        }
        List<Integer> result = new ArrayList<>();
        for (int s = 0; s < spans.size(); s++) {
            if (columnOf[s] == null) {
                int[] span = spans.get(s);
                int best = 0;
                double bestScore = Double.NEGATIVE_INFINITY;
                for (int c = 0; c < cells; c++) {
                    TextBox cell = header.boxes().get(c);
                    int overlap = overlap(span, cell);
                    double score = overlap > 0 ? overlap : -Math.abs(cell.centerX() - (span[0] + span[1]) / 2.0);
                    if (score > bestScore) {
                        bestScore = score;
                        best = c;
                    }
                }
                columnOf[s] = best;
            }
            result.add(columnOf[s]);
        }
        return result;
    }

    private static int overlap(int[] span, TextBox cell) {
        return Math.min(span[1], cell.right()) - Math.max(span[0], cell.left());
    }

    private static String markdownRow(List<String> cells) {
        return "| " + String.join(" | ", cells) + " |";
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int n = sorted.size();
        if (n == 0) {
            return 0;
        }
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }
}
