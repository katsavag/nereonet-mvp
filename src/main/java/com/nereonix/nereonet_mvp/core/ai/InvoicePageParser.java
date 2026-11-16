package com.nereonix.nereonet_mvp.core.ai;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nereonix.nereonet_mvp.core.ai.PageReading.ReadLine;

/**
 * Turns one page written as markdown (its text, and its tables as markdown) into a PageReading. Plain Java, no
 * model: FakeInvoiceReader's sample pages are read with it (ClaudeInvoiceReader gets the fields from Claude and
 * shares only InvoiceNumbers).
 *
 * The table of lines is the first markdown table whose header row names its columns, in English or Greek (accents and
 * case do not matter, a cell may hold both: "Περιγραφή / Description"):
 *   No: No, #, Α/Α, A/A, Line · Description: Description, Item, Περιγραφή, Είδος · Unit: Unit, UoM, Μ.Μ., Μον.
 *   Qty: Qty, Quantity, Ποσ., Ποσότητα · Unit price: Unit price, Price, Τιμή · Amount: Amount, Total, Value, Αξία.
 * It needs Description, Qty and one of Unit price or Amount. Each row after it is a line, except: an empty row; a row
 * without a number in No, Qty, Unit price and Amount, whose description continues the line before; and a row whose
 * description is a total ("Page total", "Σύνολο") with no line number, which is read as that total.
 * Numbers in both formats (InvoiceNumbers, with the separator most numbers of the page use).
 *
 * From the text outside the table: the supplier (the first heading, else the first line that is not a label), the
 * invoice number, the date (d/m/y), the customer's order reference ("Your order", "Order ref", "Παραγγελία"),
 * "Page x of y" ("Σελίδα x από y"), the page total, net, VAT and total (the last number on their line).
 */
public final class InvoicePageParser {

    /** A table given apart from the text and the confidence of its lines (null: not said). */
    public record Table(String markdown, Double confidence) {
    }

    enum Column {
        NO, DESCRIPTION, UNIT, QUANTITY, UNIT_PRICE, AMOUNT
    }

    private static final Pattern INLINE_TABLE = Pattern.compile("(?m)(^[ \\t]*\\|.*\\|[ \\t]*$\\R?)+");
    private static final Pattern PLACEHOLDER = Pattern.compile("!?\\[(tbl|img)-[^\\]]*\\]\\([^)]*\\)");
    private static final Pattern SEPARATOR_ROW = Pattern.compile("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$");

    private static final Pattern INVOICE_NUMBER = Pattern.compile(
            "(?iu)(?:invoice\\s*(?:no|number|nr|#)\\.?|αρ\\.?\\s*τιμολογιου|αριθμοσ\\s*τιμολογιου)"
                    // A second label after a slash ("Αρ. Τιμολογίου / Invoice No:"), however it is written.
                    + "(?:\\s*/[^:\\n]{1,25}:)?\\s*[:#.]?\\s*"
                    + "([A-Z0-9Α-Ω][A-Z0-9Α-Ω/\\-]{0,29})");
    private static final Pattern DATE = Pattern.compile(
            "(?iu)(?:date|ημερομηνια|ημ/νια)\\s*[:.]?\\s*(\\d{1,2})[./-](\\d{1,2})[./-](\\d{2,4})");
    private static final Pattern ISO_DATE = Pattern.compile(
            "(?iu)(?:date|ημερομηνια|ημ/νια)\\s*[:.]?\\s*(\\d{4})-(\\d{2})-(\\d{2})");
    private static final Pattern ORDER_REFERENCE = Pattern.compile(
            "(?iu)(?:your\\s*order(?:\\s*(?:number|nr|no|reference|ref))?|order\\s*ref(?:erence)?|order\\s*no"
                    + "|customer\\s*order(?:\\s*(?:number|nr|no|reference|ref))?|your\\s*ref(?:erence)?"
                    + "|(?<![A-Z])p\\.?\\s?o\\.?\\s*(?:number|no)"
                    + "|παραγγελια(?:\\s*πελατη)?|αρ\\.?\\s*παραγγελιασ)\\.?\\s*[:#.]?\\s*"
                    + "([A-Z0-9Α-Ω][A-Z0-9Α-Ω/\\-]{0,29}(?: \\d[A-Z0-9Α-Ω/\\-]{0,20})?)");
    private static final Pattern PAGE = Pattern.compile(
            "(?iu)(?:page|σελιδα|σελ\\.)\\s*(\\d{1,2})\\s*(?:of|/|απο)\\s*(\\d{1,2})");

    private static final List<String> PAGE_TOTAL = List.of("page total", "συνολο σελιδας", "carried forward",
            "σε μεταφορα", "εις μεταφοραν");
    private static final List<String> NET = List.of("net amount", "net total", "net value", "καθαρη αξια",
            "συνολο καθαρης", "subtotal", "net");
    private static final List<String> VAT = List.of("vat", "φ.π.α", "φπα", "tax");
    private static final List<String> TOTAL = List.of("grand total", "total amount", "amount due", "total due",
            "πληρωτεο", "γενικο συνολο", "τελικο συνολο", "συνολο", "total");

    private InvoicePageParser() {
    }

    /**
     * The page: markdown is the page's text (tables may be inline, or placeholders like [tbl-0.md](tbl-0.md) for the
     * ones given separately in tables). defaultConfidence applies to lines of an inline table.
     */
    public static PageReading parse(String markdown, List<Table> tables, Double defaultConfidence) {
        String text = markdown == null ? "" : markdown;
        List<Table> all = new ArrayList<>(tables == null ? List.of() : tables);
        // Tables written inline in the text (table_format null, or the fake).
        Matcher inline = INLINE_TABLE.matcher(text);
        while (inline.find()) {
            all.add(new Table(inline.group(), defaultConfidence));
        }
        String outside = PLACEHOLDER.matcher(INLINE_TABLE.matcher(text).replaceAll("\n")).replaceAll(" ");

        List<String> numbers = new ArrayList<>();
        numbers.add(outside);
        all.forEach(table -> numbers.add(table.markdown()));
        Character decimal = InvoiceNumbers.decimalSeparator(numbers);

        Header header = new Header();
        List<ReadLine> lines = null;
        StringBuilder otherTables = new StringBuilder();
        for (Table table : all) {
            List<List<String>> rows = rows(table.markdown());
            int headerRow = -1;
            Column[] columns = null;
            for (int i = 0; i < rows.size() && columns == null; i++) {
                columns = columns(rows.get(i));
                headerRow = i;
            }
            if (columns == null || lines != null) {
                // Not a table of lines (or a second one): its text is read like the page's text.
                rows.forEach(row -> otherTables.append(String.join(" ", row)).append('\n'));
                continue;
            }
            lines = lines(rows.subList(headerRow + 1, rows.size()), columns, decimal, table.confidence(), header);
        }
        readText(outside + "\n" + otherTables, decimal, header);
        boolean found = lines != null;
        return new PageReading(found, found ? List.copyOf(lines) : List.of(), header.supplier, header.invoiceNumber,
                header.date, header.orderReference, header.page, header.pages, header.pageTotal, header.net,
                header.vat, header.total);
    }

    /** Accents dropped, lower case: "Περιγραφή" is "περιγραφη". */
    static String fold(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replace('ς', 'σ');
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static final class Header {
        String supplier;
        String invoiceNumber;
        LocalDate date;
        String orderReference;
        Integer page;
        Integer pages;
        BigDecimal pageTotal;
        BigDecimal net;
        BigDecimal vat;
        BigDecimal total;
    }

    private static List<List<String>> rows(String markdown) {
        List<List<String>> rows = new ArrayList<>();
        for (String line : markdown.split("\\R")) {
            String row = line.strip();
            if (!row.startsWith("|") || SEPARATOR_ROW.matcher(row).matches()) {
                continue;
            }
            String inner = row.substring(1, row.endsWith("|") && row.length() > 1 ? row.length() - 1 : row.length());
            rows.add(Arrays.stream(inner.split("(?<!\\\\)\\|", -1)).map(cell -> cell.replace("\\|", "|")
                    .replace("**", "").replace("<br>", " ").strip()).toList());
        }
        return rows;
    }

    // The columns this header row names; null when it is not the header of a table of lines.
    static Column[] columns(List<String> cells) {
        Column[] columns = new Column[cells.size()];
        boolean description = false;
        boolean quantity = false;
        boolean money = false;
        for (int i = 0; i < cells.size(); i++) {
            Column column = column(fold(cells.get(i)));
            if (column != null && Arrays.asList(columns).contains(column)) {
                column = null; // the first column of a kind wins
            }
            columns[i] = column;
            description |= column == Column.DESCRIPTION;
            quantity |= column == Column.QUANTITY;
            money |= column == Column.UNIT_PRICE || column == Column.AMOUNT;
        }
        return description && quantity && money ? columns : null;
    }

    private static Column column(String cell) {
        String c = cell.replaceAll("\\s+", " ").strip();
        if (c.isEmpty()) {
            return null;
        }
        if (c.contains("price") || c.contains("τιμη") || c.contains("rate")) {
            return Column.UNIT_PRICE;
        }
        if (c.contains("amount") || c.contains("αξια") || c.contains("value") || c.equals("total")
                || c.contains("συνολο")) {
            return Column.AMOUNT;
        }
        if (c.contains("qty") || c.contains("quantity") || c.contains("ποσ")) {
            return Column.QUANTITY;
        }
        if (c.contains("unit") || c.contains("uom") || c.contains("μ.μ") || c.equals("μμ") || c.contains("μον")) {
            return Column.UNIT;
        }
        if (c.contains("descr") || c.contains("περιγρ") || c.contains("ειδοσ") || c.equals("item")
                || c.contains("product")) {
            return Column.DESCRIPTION;
        }
        // "Α/Α No" with Greek and Latin letters mixed ("α/a no"): by its last word.
        if (c.contains("α/α") || c.contains("a/a") || c.matches("(?:no\\.?|#|line|nr\\.?|s/n|α\\.α\\.?)")
                || c.matches(".*[\\s/](?:no|νο)\\.?")) {
            return Column.NO;
        }
        return null;
    }

    private static List<ReadLine> lines(List<List<String>> rows, Column[] columns, Character decimal,
                                        Double confidence, Header header) {
        List<ReadLine> lines = new ArrayList<>();
        for (List<String> row : rows) {
            String no = cell(row, columns, Column.NO);
            String description = cell(row, columns, Column.DESCRIPTION);
            String unit = cell(row, columns, Column.UNIT);
            String quantity = cell(row, columns, Column.QUANTITY);
            String price = cell(row, columns, Column.UNIT_PRICE);
            String amount = cell(row, columns, Column.AMOUNT);
            if (row.stream().allMatch(String::isBlank)) {
                continue;
            }
            Integer number = no.matches("\\d{1,4}\\.?") ? Integer.valueOf(no.replace(".", "")) : null;
            // A total written in the table: "Page total", "Σύνολο".
            String label = fold(description.isBlank() ? String.join(" ", row) : description);
            if (number == null && quantity.isBlank() && isTotalLabel(label)) {
                BigDecimal value = InvoiceNumbers.parseCell(amount, decimal);
                if (value == null) {
                    value = InvoiceNumbers.lastNumber(String.join(" ", row), decimal);
                }
                assignTotal(label, value, header);
                continue;
            }
            boolean anyNumber = number != null || !quantity.isBlank() || !price.isBlank() || !amount.isBlank();
            if (!anyNumber) {
                // A description going on to the next row.
                if (!lines.isEmpty() && !description.isBlank()) {
                    ReadLine last = lines.removeLast();
                    lines.add(new ReadLine(last.no(), (last.description() + " " + description).strip(), last.unit(),
                            last.quantity(), last.unitPrice(), last.amount(), last.raw() + "\n" + raw(row),
                            last.confidence()));
                }
                continue;
            }
            lines.add(new ReadLine(number, description.isBlank() ? null : description, unit.isBlank() ? null : unit,
                    InvoiceNumbers.parseCell(quantity, decimal), InvoiceNumbers.parseCell(price, decimal),
                    InvoiceNumbers.parseCell(amount, decimal), raw(row), confidence));
        }
        return lines;
    }

    private static String cell(List<String> row, Column[] columns, Column column) {
        for (int i = 0; i < columns.length && i < row.size(); i++) {
            if (columns[i] == column) {
                return row.get(i);
            }
        }
        return "";
    }

    private static String raw(List<String> row) {
        return "| " + String.join(" | ", row) + " |";
    }

    // "VAT no. EL 000000000", "Α.Φ.Μ.": a registration number, never an amount.
    private static final Pattern VAT_NUMBER = Pattern.compile(
            "(?:vat|φ\\.?π\\.?α\\.?)\\s*(?:no|nr|number|reg|id)(?![\\p{L}])|α\\.?φ\\.?μ");

    private static boolean isTotalLabel(String label) {
        if (VAT_NUMBER.matcher(label).find()) {
            return false;
        }
        return containsAny(label, PAGE_TOTAL) || containsAny(label, NET) || containsAny(label, VAT)
                || containsAny(label, TOTAL);
    }

    private static void assignTotal(String label, BigDecimal value, Header header) {
        if (value == null) {
            return;
        }
        if (containsAny(label, PAGE_TOTAL)) {
            header.pageTotal = value;
        } else if (containsAny(label, VAT)) {
            header.vat = value;
        } else if (containsAny(label, NET)) {
            header.net = value;
        } else if (containsAny(label, TOTAL)) {
            header.total = value;
        }
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String word : words) {
            if (Pattern.compile("(?<![\\p{L}])" + Pattern.quote(fold(word)) + "(?![\\p{L}])").matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static void readText(String text, Character decimal, Header header) {
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.replace("**", "").replace("__", "").replace('|', ' ').strip();
            boolean heading = line.startsWith("#");
            line = line.replaceFirst("^#+\\s*", "").strip();
            if (line.isEmpty()) {
                continue;
            }
            String folded = fold(line);
            String upperFolded = fold(line).toUpperCase(Locale.ROOT);
            if (header.invoiceNumber == null) {
                header.invoiceNumber = value(INVOICE_NUMBER, upperFolded);
            }
            if (header.orderReference == null) {
                header.orderReference = value(ORDER_REFERENCE, upperFolded);
            }
            if (header.date == null) {
                header.date = date(folded);
            }
            Matcher page = PAGE.matcher(folded);
            if (header.page == null && page.find()) {
                header.page = Integer.valueOf(page.group(1));
                header.pages = Integer.valueOf(page.group(2));
            }
            boolean label = isTotalLabel(folded);
            if (label && !page.reset().find()) {
                assignTotal(folded, InvoiceNumbers.lastNumber(line, decimal), header);
            }
            if (header.supplier == null && heading && !isLabel(folded)) {
                header.supplier = cut(line, 200);
            }
        }
        if (header.supplier == null) {
            for (String rawLine : text.split("\\R")) {
                String line = rawLine.replace("**", "").replaceFirst("^#+\\s*", "").replace('|', ' ').strip();
                String folded = fold(line);
                if (line.codePoints().filter(Character::isLetter).count() >= 3 && !isLabel(folded)
                        && !isTotalLabel(folded)) {
                    header.supplier = cut(line, 200);
                    break;
                }
            }
        }
    }

    // A line that is a label or a title of the document, never the supplier.
    private static boolean isLabel(String folded) {
        return folded.contains("sample") || folded.contains("invoice") || folded.contains("τιμολογ")
                || folded.contains("delivery note") || folded.contains("δελτιο") || folded.contains("page ")
                || folded.contains("σελιδα") || folded.contains("date") || folded.contains("ημερομηνια")
                || folded.contains("order") || folded.contains("παραγγελ");
    }

    private static String value(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(1);
            if (value.chars().anyMatch(Character::isDigit)) {
                return cut(value, 60);
            }
        }
        return null;
    }

    private static LocalDate date(String folded) {
        try {
            Matcher iso = ISO_DATE.matcher(folded);
            if (iso.find()) {
                return LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)),
                        Integer.parseInt(iso.group(3)));
            }
            Matcher matcher = DATE.matcher(folded);
            if (matcher.find()) {
                int year = Integer.parseInt(matcher.group(3));
                return LocalDate.of(year < 100 ? 2000 + year : year, Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(1)));
            }
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
        return null;
    }

    private static String cut(String text, int max) {
        String value = text.strip();
        return value.length() > max ? value.substring(0, max) : value;
    }
}
