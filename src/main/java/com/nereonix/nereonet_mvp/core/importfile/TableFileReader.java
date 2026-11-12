package com.nereonix.nereonet_mvp.core.importfile;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

// Reads an uploaded .xlsx, .xls or .csv file of any structure (P24): every visible sheet of a workbook, or the one
// table of a CSV file. Empty rows and sheets are left out; trailing empty cells of a row are dropped.
public final class TableFileReader {

    // Excel on a Windows PC in Western Europe saves "CSV (comma delimited)" in this code page, not in UTF-8.
    private static final Charset CSV_FALLBACK = Charset.forName("windows-1252");

    private TableFileReader() {
    }

    /**
     * The file type comes from the file name. Never empty: a file without any non-empty row is one sheet with no rows.
     * Throws IOException if the type is not supported or the file is unreadable.
     */
    public static List<ImportSheet> read(String filename, InputStream in) throws IOException {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            return readWorkbook(in);
        }
        if (name.endsWith(".csv")) {
            return List.of(new ImportSheet(filename, readCsv(in)));
        }
        throw new IOException("Not an .xlsx, .xls or .csv file");
    }

    private static List<ImportSheet> readWorkbook(InputStream in) throws IOException {
        // Shows each cell as Excel does, so the number 9074729 becomes "9074729", not "9074729.0".
        DataFormatter formatter = new DataFormatter();
        List<ImportSheet> sheets = new ArrayList<>();
        // WorkbookFactory tells .xlsx from .xls by the content.
        try (Workbook workbook = WorkbookFactory.create(in)) {
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                // Hidden sheets usually hold lookup lists, not the data.
                if (workbook.isSheetHidden(s) || workbook.isSheetVeryHidden(s)) {
                    continue;
                }
                Sheet sheet = workbook.getSheetAt(s);
                List<ImportSheet.Row> rows = new ArrayList<>();
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (int i = 0; i < Math.max(row.getLastCellNum(), 0); i++) {
                        cells.add(formatter.formatCellValue(row.getCell(i), evaluator).strip());
                    }
                    addIfNotEmpty(rows, row.getRowNum() + 1, cells);
                }
                if (!rows.isEmpty()) {
                    sheets.add(new ImportSheet(sheet.getSheetName(), rows));
                }
            }
        } catch (RuntimeException e) {
            // POI reports a broken or unknown file with runtime exceptions.
            throw new IOException("Unreadable workbook", e);
        }
        if (sheets.isEmpty()) {
            sheets.add(new ImportSheet("", List.of()));
        }
        return sheets;
    }

    private static List<ImportSheet.Row> readCsv(InputStream in) throws IOException {
        String text = decode(in.readAllBytes());
        // Excel adds a byte order mark to UTF-8 CSV files.
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        // Excel in Greek and most European locales saves CSV with ";". The first lines decide, so a title line
        // above the table does not.
        String start = String.join("\n", text.lines().limit(10).toList());
        char delimiter = count(start, ';') > count(start, ',') ? ';' : ',';
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                // Keep empty lines as records, so the record number stays the line number.
                .setIgnoreEmptyLines(false)
                .get();
        List<ImportSheet.Row> rows = new ArrayList<>();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            for (CSVRecord record : parser) {
                List<String> cells = record.stream().map(String::strip).toList();
                addIfNotEmpty(rows, (int) record.getRecordNumber(), cells);
            }
        } catch (RuntimeException e) {
            throw new IOException("Unreadable .csv file", e);
        }
        return rows;
    }

    // UTF-8 when the bytes are valid UTF-8, otherwise the Windows code page Excel uses.
    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, CSV_FALLBACK);
        }
    }

    private static void addIfNotEmpty(List<ImportSheet.Row> rows, int number, List<String> cells) {
        int end = cells.size();
        while (end > 0 && cells.get(end - 1).isEmpty()) {
            end--;
        }
        if (end > 0) {
            rows.add(new ImportSheet.Row(number, List.copyOf(cells.subList(0, end))));
        }
    }

    private static long count(String text, char c) {
        return text.chars().filter(ch -> ch == c).count();
    }
}
