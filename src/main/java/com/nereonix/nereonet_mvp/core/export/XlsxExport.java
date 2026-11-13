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

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The office's exports as Excel workbooks (P39; CSV before): one sheet named after the list, the header row bold and
 * frozen with an autofilter, then one row per record. Cell types follow the values: a LocalDate is a real date cell
 * shown "dd MMM yyyy", an Instant a date and time cell in UTC ("dd MMM yyyy hh:mm"), a number a number, anything else
 * text (an IMO number stays text, as an identifier), null an empty cell. Column widths fit the longest value (between
 * 8 and 60 characters), worked out from the text itself, so no fonts are needed on the server.
 * The column names are the file format, never translated.
 */
public final class XlsxExport {

    /** The content type of a .xlsx file. */
    public static final MediaType CONTENT_TYPE =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    static final String DATE_FORMAT = "dd mmm yyyy";
    static final String DATE_TIME_FORMAT = "dd mmm yyyy hh:mm";
    private static final int MIN_WIDTH = 8;
    private static final int MAX_WIDTH = 60;
    // The width of a value as Excel shows it, for the column widths.
    private static final DateTimeFormatter DATE_TEXT = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME_TEXT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH);

    /** A workbook to download: its file name (e.g. certificates-2026-11-13.xlsx) and content. */
    public record File(String filename, byte[] content) {

        /** The download: attachment, the .xlsx content type, never stored by a cache. */
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

    private XlsxExport(String sheetName, List<String> header) {
        this.sheetName = sheetName;
        this.header = List.copyOf(header);
    }

    /** sheetName: the list's name, e.g. "Certificates"; header: the column names, in order. */
    public static XlsxExport sheet(String sheetName, List<String> header) {
        return new XlsxExport(sheetName, header);
    }

    /** One record, its values in the header's order. */
    public XlsxExport row(List<?> values) {
        rows.add(values);
        return this;
    }

    /** The workbook as a file named baseName + ".xlsx", e.g. "certificates-2026-11-13". */
    public File file(String baseName) {
        return new File(baseName + ".xlsx", bytes());
    }

    byte[] bytes() {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Font boldFont = workbook.createFont();
            boldFont.setBold(true);
            CellStyle bold = workbook.createCellStyle();
            bold.setFont(boldFont);
            CellStyle date = workbook.createCellStyle();
            date.setDataFormat(workbook.createDataFormat().getFormat(DATE_FORMAT));
            CellStyle dateTime = workbook.createCellStyle();
            dateTime.setDataFormat(workbook.createDataFormat().getFormat(DATE_TIME_FORMAT));

            Sheet sheet = workbook.createSheet(WorkbookUtil.createSafeSheetName(sheetName));
            int[] widths = new int[header.size()];
            Row head = sheet.createRow(0);
            for (int i = 0; i < header.size(); i++) {
                Cell cell = head.createCell(i);
                cell.setCellValue(header.get(i));
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
                            shown = value.toString();
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
}
