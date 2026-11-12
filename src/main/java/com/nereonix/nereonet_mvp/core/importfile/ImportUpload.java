package com.nereonix.nereonet_mvp.core.importfile;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// An uploaded file while it is being imported (P24), kept in the HTTP session with its review form (ImportForm) from
// the upload until the import is done or cancelled: the file's sheets, the chosen sheet and header row, and the column
// and value mapping. Nothing of it is saved before the last step.
public class ImportUpload implements Serializable {

    /** Rows shown in the mapping step's preview. */
    public static final int PREVIEW_ROWS = 5;

    private final String filename;
    private final List<ImportSheet> sheets;
    private int sheetIndex;
    // Index in the chosen sheet's rows of the row with the column names.
    private int headerIndex;

    // Field key (or ImportField.secondKey) -> column index.
    private final Map<String, Integer> columns = new LinkedHashMap<>();
    // Field key -> (ImportNames.compact(value from the file) -> canonical value). "" leaves the value as it is,
    // to be corrected per row on the review screen.
    private final Map<String, Map<String, String>> values = new HashMap<>();

    // When the mapping was filled in from one saved for a file with the same column names; null otherwise.
    private Instant savedMappingAt;
    // True once the user confirmed the columns (or the file has exactly the template's columns).
    private boolean columnsConfirmed;
    // True when the file has exactly the template's columns, so the mapping step was skipped.
    private boolean template;

    public ImportUpload(String filename, List<ImportSheet> sheets) {
        this.filename = filename == null ? "" : filename;
        this.sheets = new ArrayList<>(sheets);
    }

    public String getFilename() { return filename; }
    public List<ImportSheet> getSheets() { return sheets; }
    public int getSheetIndex() { return sheetIndex; }
    public int getHeaderIndex() { return headerIndex; }
    public Map<String, Integer> getColumns() { return columns; }
    public Map<String, Map<String, String>> getValues() { return values; }
    public Instant getSavedMappingAt() { return savedMappingAt; }
    public boolean isColumnsConfirmed() { return columnsConfirmed; }
    public boolean isTemplate() { return template; }

    public boolean isWorkbookWithSheets() { return sheets.size() > 1; }

    /** Chooses the sheet and the header row, and forgets the mapping. */
    public void choose(int sheetIndex, int headerIndex) {
        this.sheetIndex = Math.clamp(sheetIndex, 0, sheets.size() - 1);
        this.headerIndex = Math.clamp(headerIndex, 0, Math.max(sheet().rows().size() - 1, 0));
        columns.clear();
        values.clear();
        savedMappingAt = null;
        columnsConfirmed = false;
        template = false;
    }

    public void setSavedMappingAt(Instant savedMappingAt) { this.savedMappingAt = savedMappingAt; }
    public void setColumnsConfirmed(boolean columnsConfirmed) { this.columnsConfirmed = columnsConfirmed; }
    public void setTemplate(boolean template) { this.template = template; }

    public ImportSheet sheet() {
        return sheets.get(sheetIndex);
    }

    /** The row with the column names, or null when the sheet has no rows. */
    public ImportSheet.Row headerRow() {
        List<ImportSheet.Row> rows = sheet().rows();
        return rows.isEmpty() ? null : rows.get(headerIndex);
    }

    /** The column names, one per column of the sheet ("" where the header row has none). */
    public List<String> header() {
        ImportSheet.Row row = headerRow();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < sheet().width(); i++) {
            names.add(row == null ? "" : row.cell(i));
        }
        return names;
    }

    /** The rows after the header row: one record each. */
    public List<ImportSheet.Row> dataRows() {
        List<ImportSheet.Row> rows = sheet().rows();
        return rows.isEmpty() ? List.of() : rows.subList(headerIndex + 1, rows.size());
    }

    /** The rows that may hold the column names, for the header row choice: the first ones of the sheet. */
    public List<ImportSheet.Row> headerCandidates() {
        List<ImportSheet.Row> rows = sheet().rows();
        return rows.subList(0, Math.min(rows.size(), ColumnGuesser.HEADER_ROWS));
    }

    public List<ImportSheet.Row> preview() {
        List<ImportSheet.Row> rows = dataRows();
        return rows.subList(0, Math.min(rows.size(), PREVIEW_ROWS));
    }

    /** "A", "B", ..., "Z", "AA": the spreadsheet column letter. */
    public static String letter(int column) {
        StringBuilder letters = new StringBuilder();
        for (int n = column + 1; n > 0; n = (n - 1) / 26) {
            letters.insert(0, (char) ('A' + (n - 1) % 26));
        }
        return letters.toString();
    }

    /** The first non-empty value of the column, or "". */
    public String example(int column) {
        return dataRows().stream().map(row -> row.cell(column)).filter(value -> !value.isEmpty()).findFirst()
                .orElse("");
    }

    /** The columns no field is mapped to that have a name or a value. */
    public List<Integer> ignoredColumns() {
        Set<Integer> mapped = new LinkedHashSet<>(columns.values());
        List<String> header = header();
        List<Integer> ignored = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            if (!mapped.contains(i) && (!header.get(i).isEmpty() || !example(i).isEmpty())) {
                ignored.add(i);
            }
        }
        return ignored;
    }

    /** The column of the field, or null when it has none. */
    public Integer column(String key) {
        return columns.get(key);
    }

    /** The field's value in the row as in the file; a two-column field joins its two values with a space. */
    public String value(ImportSheet.Row row, ImportField field) {
        String value = cell(row, field.key());
        if (field.isTwoColumns()) {
            String second = cell(row, field.secondKey());
            value = (value + " " + second).strip();
        }
        return value;
    }

    /**
     * The field's value in the row, matched: the canonical value when it matches exactly or the user chose one for
     * it in the value mapping, otherwise the value as in the file.
     */
    public String matchedValue(ImportSheet.Row row, ImportField field) {
        String raw = value(row, field);
        if (field.values() == null || raw.isEmpty()) {
            return raw;
        }
        String exact = field.values().exact(raw);
        if (exact != null) {
            return exact;
        }
        String chosen = values.getOrDefault(field.key(), Map.of()).get(ImportNames.compact(raw));
        return chosen == null || chosen.isEmpty() ? raw : chosen;
    }

    private String cell(ImportSheet.Row row, String key) {
        Integer column = columns.get(key);
        return column == null ? "" : row.cell(column);
    }
}
