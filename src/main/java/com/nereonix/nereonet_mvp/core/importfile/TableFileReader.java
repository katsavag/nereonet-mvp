package com.nereonix.nereonet_mvp.core.importfile;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
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
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

// Reads an uploaded .xlsx (first sheet) or .csv (UTF-8) file into a TableFile. Empty rows are skipped.
public final class TableFileReader {

    private TableFileReader() {
    }

    /** The file type comes from the file name. Throws IOException if the type is not supported or the file is unreadable. */
    public static TableFile read(String filename, InputStream in) throws IOException {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        List<TableFile.Row> rows;
        if (name.endsWith(".xlsx")) {
            rows = readXlsx(in);
        } else if (name.endsWith(".csv")) {
            rows = readCsv(in);
        } else {
            throw new IOException("Not an .xlsx or .csv file");
        }
        if (rows.isEmpty()) {
            return new TableFile(List.of(), List.of());
        }
        return new TableFile(rows.getFirst().cells(), rows.subList(1, rows.size()));
    }

    private static List<TableFile.Row> readXlsx(InputStream in) throws IOException {
        // Shows each cell as Excel does, so the number 9074729 becomes "9074729", not "9074729.0".
        DataFormatter formatter = new DataFormatter();
        List<TableFile.Row> rows = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(in)) {
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                for (int i = 0; i < Math.max(row.getLastCellNum(), 0); i++) {
                    cells.add(formatter.formatCellValue(row.getCell(i), evaluator).strip());
                }
                addIfNotEmpty(rows, row.getRowNum() + 1, cells);
            }
        } catch (RuntimeException e) {
            // POI reports a broken or non-xlsx file with runtime exceptions.
            throw new IOException("Unreadable .xlsx file", e);
        }
        return rows;
    }

    private static List<TableFile.Row> readCsv(InputStream in) throws IOException {
        String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        // Excel adds a byte order mark to UTF-8 CSV files.
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        // Excel in Greek and most European locales saves CSV with ";".
        String firstLine = text.lines().findFirst().orElse("");
        char delimiter = count(firstLine, ';') > count(firstLine, ',') ? ';' : ',';
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                // Keep empty lines as records, so the record number stays the line number.
                .setIgnoreEmptyLines(false)
                .get();
        List<TableFile.Row> rows = new ArrayList<>();
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

    private static void addIfNotEmpty(List<TableFile.Row> rows, int number, List<String> cells) {
        if (cells.stream().anyMatch(cell -> !cell.isEmpty())) {
            rows.add(new TableFile.Row(number, cells));
        }
    }

    private static long count(String text, char c) {
        return text.chars().filter(ch -> ch == c).count();
    }
}
