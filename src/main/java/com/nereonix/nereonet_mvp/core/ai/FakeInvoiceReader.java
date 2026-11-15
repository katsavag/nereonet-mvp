package com.nereonix.nereonet_mvp.core.ai;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.nereonix.nereonet_mvp.core.ai.InvoiceReadingException.Reason;
import com.nereonix.nereonet_mvp.core.ai.PageReading.ReadLine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads instead of the local OCR when app.invoice.fake is true: dev screens without the models, and the tests. Whatever
 * the photo shows, page 1 is page 1 of the synthetic sample invoice A-4471 (samples/invoice/, app.invoice.sample-dir;
 * read from the working directory) and page 2 its page 2: their lines and text are written out as the OCR writes them
 * (a markdown table, Greek number format 1.234,56) and go through the same InvoicePageParser as the local OCR's.
 * Page 3 and later have no table. Every line's confidence is 0.97.
 * Tests change that with fail, failPage, noTable, dropLine, lowConfidence, change and orderReference; reset undoes it.
 */
@Component
@ConditionalOnProperty(name = "app.invoice.fake", havingValue = "true")
public class FakeInvoiceReader implements InvoiceReader {

    public static final String SAMPLE = "invoice-A-4471.expected.json";
    static final double CONFIDENCE = 0.97;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final Path sampleDir;
    private volatile JsonNode sample;

    private volatile Reason failure;
    private final Map<Integer, Reason> pageFailures = new HashMap<>();
    private final Set<Integer> noTable = new HashSet<>();
    private final Set<Integer> dropped = new HashSet<>();
    private final Set<Integer> low = new HashSet<>();
    private final Map<Integer, String[]> changes = new HashMap<>();
    private volatile String orderReference;
    private final List<Integer> pagesRead = Collections.synchronizedList(new ArrayList<>());

    @Override
    public boolean configured() {
        return true;
    }

    public FakeInvoiceReader(@Value("${app.invoice.sample-dir:samples/invoice}") String sampleDir) {
        this.sampleDir = Path.of(sampleDir);
    }

    @Override
    public synchronized PageReading readPage(Page page) {
        pagesRead.add(page.number());
        if (failure != null) {
            throw new InvoiceReadingException(failure, "Fake " + failure);
        }
        Reason pageFailure = pageFailures.get(page.number());
        if (pageFailure != null) {
            throw new InvoiceReadingException(pageFailure, "Fake " + pageFailure);
        }
        PageReading reading = InvoicePageParser.parse(markdown(page.number()), List.of(), CONFIDENCE);
        List<ReadLine> lines = new ArrayList<>();
        for (ReadLine line : reading.lines()) {
            lines.add(low.contains(line.no()) ? line.withConfidence(0.5) : line);
        }
        reading = reading.withLines(lines);
        return orderReference != null ? reading.withOrderReference(orderReference) : reading;
    }

    /** The markdown the fake "OCR" gives for this page. */
    public synchronized String markdown(int pageNumber) {
        JsonNode invoice = sample();
        StringBuilder md = new StringBuilder();
        md.append("SAMPLE: synthetic invoice for testing. Fictional company, not a real document.\n\n");
        md.append("# ").append(invoice.path("supplier").asString()).append("\n\n");
        md.append("## ΤΙΜΟΛΟΓΙΟ - ΔΕΛΤΙΟ ΑΠΟΣΤΟΛΗΣ / INVOICE - DELIVERY NOTE\n\n");
        md.append("Αρ. Τιμολογίου / Invoice No: **").append(invoice.path("invoiceNumber").asString()).append("**\n\n");
        md.append("Ημερομηνία / Date: **").append(LocalDate.parse(invoice.path("date").asString())
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))).append("**\n\n");
        md.append("Παραγγελία πελάτη / Your order: **").append(invoice.path("orderReference").asString())
                .append("**\n\n");
        md.append("Σελίδα ").append(pageNumber).append(" από 2 / Page ").append(pageNumber).append(" of 2\n\n");
        if (pageNumber > 2 || noTable.contains(pageNumber)) {
            md.append("(the photo shows no table)\n");
            return md.toString();
        }
        md.append("| Α/Α No | Περιγραφή / Description | Μ.Μ. Unit | Ποσ. / Qty | Τιμή / Unit price | Αξία / Amount |\n");
        md.append("|---|---|---|---|---|---|\n");
        for (JsonNode line : invoice.path("lines")) {
            int no = line.path("no").asInt();
            if (line.path("page").asInt() != pageNumber || dropped.contains(no)) {
                continue;
            }
            String unit = line.path("unit").asString();
            String[] cells = {String.valueOf(no), line.path("description").asString(), unit,
                    quantity(new BigDecimal(line.path("quantity").asString()), unit),
                    money(new BigDecimal(line.path("unitPrice").asString())),
                    money(new BigDecimal(line.path("amount").asString()))};
            String[] change = changes.get(no);
            if (change != null) {
                for (int i = 0; i < 3; i++) {
                    if (change[i] != null) {
                        cells[3 + i] = change[i];
                    }
                }
            }
            md.append("| ").append(String.join(" | ", cells)).append(" |\n");
        }
        md.append('\n');
        JsonNode pageInfo = invoice.path("pages").path(pageNumber - 1);
        md.append("**Σύνολο σελίδας / Page total:** ").append(money(new BigDecimal(pageInfo.path("pageTotal").asString())))
                .append("\n\n");
        if (pageNumber == 2) {
            md.append("**Καθαρή αξία / Net amount:** ").append(money(new BigDecimal(invoice.path("net").asString())))
                    .append("\n\n");
            md.append("**Φ.Π.Α. 13% / VAT 13%:** ").append(money(new BigDecimal(invoice.path("vat").asString())))
                    .append("\n\n");
            md.append("**Πληρωτέο / Total:** ").append(money(new BigDecimal(invoice.path("total").asString())))
                    .append(" EUR\n");
        }
        return md.toString();
    }

    /** The sample's expected JSON (tests compare the lines read with it). */
    public JsonNode sample() {
        JsonNode loaded = sample;
        if (loaded == null) {
            try {
                loaded = MAPPER.readTree(Files.readString(sampleDir.resolve(SAMPLE)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            sample = loaded;
        }
        return loaded;
    }

    /** From now on, every page fails so. */
    public synchronized void fail(Reason reason) {
        failure = reason;
    }

    /** From now on, this page fails so. */
    public synchronized void failPage(int page, Reason reason) {
        pageFailures.put(page, reason);
    }

    /** This page shows no table (a bad photo). */
    public synchronized void noTable(int page) {
        noTable.add(page);
    }

    /** This line of the sample is not in the OCR output (a fold in the paper). */
    public synchronized void dropLine(int no) {
        dropped.add(no);
    }

    /** This line's block has a low confidence (0.5). */
    public synchronized void lowConfidence(int no) {
        low.add(no);
    }

    /** This line's quantity, unit price and amount as the OCR writes them (null: unchanged; "" : empty cell). */
    public synchronized void change(int no, String quantity, String unitPrice, String amount) {
        changes.put(no, new String[] {quantity, unitPrice, amount});
    }

    /** Every page says this order reference. */
    public synchronized void orderReference(String reference) {
        orderReference = reference;
    }

    /** The page numbers read so far. */
    public List<Integer> pagesRead() {
        synchronized (pagesRead) {
            return List.copyOf(pagesRead);
        }
    }

    public synchronized void reset() {
        failure = null;
        pageFailures.clear();
        noTable.clear();
        dropped.clear();
        low.clear();
        changes.clear();
        orderReference = null;
        pagesRead.clear();
    }

    static String money(BigDecimal value) {
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.GERMANY)).format(value);
    }

    static String quantity(BigDecimal value, String unit) {
        return new DecimalFormat("KG".equals(unit) ? "#,##0.000" : "#,##0",
                DecimalFormatSymbols.getInstance(Locale.GERMANY)).format(value);
    }
}
