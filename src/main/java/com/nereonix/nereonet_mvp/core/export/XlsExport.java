package com.nereonix.nereonet_mvp.core.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The office's exports as Excel 97-2003 workbooks (.xls, P40; .xlsx in P39, CSV before), so the files open everywhere:
 * one sheet named after the list (at most 31 characters), the header row bold and frozen with an autofilter, then one
 * row per record. Cell types follow the values: a LocalDate is a real date cell shown "dd MMM yyyy", an Instant a date
 * and time cell in UTC ("dd MMM yyyy hh:mm"), a number a number, anything else text (an IMO number stays text, as an
 * identifier), null an empty cell. Column widths fit the longest value (between 8 and 60 characters), worked out from
 * the text itself, so no fonts are needed on the server.
 * The format's limits: at most 65,535 rows after the header and 256 columns. A bigger export is refused
 * (TooManyRowsException), never cut short; a text longer than 32,767 characters is shortened, ending in "…".
 * The column names are the file format, never translated.
 */
public final class XlsExport {

    /** The content type of a .xls file. */
    public static final MediaType CONTENT_TYPE = MediaType.parseMediaType("application/vnd.ms-excel");

    /** The data rows a sheet can hold under its header row (65,536 rows in all). */
    public static final int MAX_ROWS = SpreadsheetVersion.EXCEL97.getMaxRows() - 1;
    /** The columns a sheet can hold. */
    public static final int MAX_COLUMNS = SpreadsheetVersion.EXCEL97.getMaxColumns();
    /** The longest text a cell can hold. */
    public static final int MAX_TEXT_LENGTH = SpreadsheetVersion.EXCEL97.getMaxTextLength();

    static final String DATE_FORMAT = "dd mmm yyyy";
    static final String DATE_TIME_FORMAT = "dd mmm yyyy hh:mm";
    private static final String ELLIPSIS = "…";
    private static final int MIN_WIDTH = 8;
    private static final int MAX_WIDTH = 60;
    // The width of a value as Excel shows it, for the column widths.
    private static final DateTimeFormatter DATE_TEXT = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME_TEXT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH);

    /** The export does not fit in one .xls sheet: the office narrows the filters ("export.tooManyRows"). */
    public static class TooManyRowsException extends RuntimeException {

        /** The message key the controllers show. */
        public static final String MESSAGE = "export.tooManyRows";

        TooManyRowsException(int rows, int columns) {
            super("Too many rows for one .xls file: " + rows + " rows, " + columns + " columns");
        }
    }

    /** A workbook to download: its file name (e.g. certificates-2026-11-13.xls) and content. */
    public record File(String filename, byte[] content) {

        /** The download: attachment, the .xls content type, never stored by a cache. */
        public ResponseEntity<byte[]> response() {
            return ResponseEntity.ok()
                    .contentType(CONTENT_TYPE)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(filename).build().toString())
                    .cacheControl(CacheControl.noStore())
                    .body(content);
        }
    }

    private final String sheetName;
    private final List<String> header;
    private final List<List<?>> rows = new ArrayList<>();

    private XlsExport(String sheetName, List<String> header) {
        this.sheetName = sheetName;
        this.header = List.copyOf(header);
    }

    /** sheetName: the list's name, e.g. "Certificates"; header: the column names, in order. */
    public static XlsExport sheet(String sheetName, List<String> header) {
        return new XlsExport(sheetName, header);
    }

    /** One record, its values in the header's order. */
    public XlsExport row(List<?> values) {
        rows.add(values);
        return this;
    }

    /**
     * The workbook as a file named baseName + ".xls", e.g. "certificates-2026-11-13". TooManyRowsException when it
     * has more rows or columns than one .xls sheet holds.
     */
    public File file(String baseName) {
        return new File(baseName + ".xls", bytes());
    }

    byte[] bytes() {
        int columns = Math.max(header.size(), rows.stream().mapToInt(List::size).max().orElse(0));
        if (rows.size() > MAX_ROWS || columns > MAX_COLUMNS) {
            throw new TooManyRowsException(rows.size(), columns);
        }
        try (HSSFWorkbook workbook = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Font boldFont = workbook.createFont();
            boldFont.setBold(true);
            CellStyle bold = workbook.createCellStyle();
            bold.setFont(boldFont);
            CellStyle date = workbook.createCellStyle();
            date.setDataFormat(workbook.createDataFormat().getFormat(DATE_FORMAT));
            CellStyle dateTime = workbook.createCellStyle();
            dateTime.setDataFormat(workbook.createDataFormat().getFormat(DATE_TIME_FORMAT));

            // createSafeSheetName also keeps the name within 31 characters.
            Sheet sheet = workbook.createSheet(WorkbookUtil.createSafeSheetName(sheetName));
            int[] widths = new int[header.size()];
            Row head = sheet.createRow(0);
            for (int i = 0; i < header.size(); i++) {
                Cell cell = head.createCell(i);
                cell.setCellValue(text(header.get(i)));
                cell.setCellStyle(bold);
                widths[i] = header.get(i).length();
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<?> values = rows.get(r);
                for (int i = 0; i < values.size(); i++) {
                    Object value = values.get(i);
                    if (value == null) {
                        continue;
                    }
                    Cell cell = row.createCell(i);
                    String shown;
                    switch (value) {
                        case LocalDate d -> {
                            cell.setCellValue(d);
                            cell.setCellStyle(date);
                            shown = DATE_TEXT.format(d);
                        }
                        case Instant instant -> {
                            LocalDateTime utc = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
                            cell.setCellValue(utc);
                            cell.setCellStyle(dateTime);
                            shown = DATE_TIME_TEXT.format(utc);
                        }
                        case BigDecimal n -> {
                            cell.setCellValue(n.doubleValue());
                            shown = n.stripTrailingZeros().toPlainString();
                        }
                        case Number n -> {
                            cell.setCellValue(n.doubleValue());
                            shown = n.toString();
                        }
                        default -> {
                            shown = text(value.toString());
                            cell.setCellValue(shown);
                        }
                    }
                    if (i < widths.length) {
                        widths[i] = Math.max(widths[i], shown.length());
                    }
                }
            }
            for (int i = 0; i < widths.length; i++) {
                // A little room for the autofilter's arrow next to the header.
                sheet.setColumnWidth(i, Math.clamp(widths[i] + 3, MIN_WIDTH, MAX_WIDTH) * 256);
            }
            sheet.createFreezePane(0, 1);
            if (!header.isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(0, Math.max(rows.size(), 1), 0, header.size() - 1));
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e); // never happens with a ByteArrayOutputStream
        }
    }

    /** The text as it fits in one cell: at most MAX_TEXT_LENGTH characters, a longer one ending in "…". */
    static String text(String value) {
        if (value.length() <= MAX_TEXT_LENGTH) {
            return value;
        }
        int end = MAX_TEXT_LENGTH - ELLIPSIS.length();
        // Never between the two halves of a character outside the Basic Multilingual Plane.
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end) + ELLIPSIS;
    }
}
