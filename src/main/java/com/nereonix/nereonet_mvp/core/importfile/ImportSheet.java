package com.nereonix.nereonet_mvp.core.importfile;

import java.io.Serializable;
import java.util.List;

// One sheet of an uploaded file (a CSV file is one sheet): its non-empty rows, in order. Which row holds the column
// names is decided later (ImportUpload.headerIndex). Every cell is a trimmed string ("" when empty).
public record ImportSheet(String name, List<Row> rows) implements Serializable {

    /**
     * "Full name · Email · Rank · …": the first values of the fullest of the first rows (usually the column names),
     * for telling sheets apart.
     */
    public String columnsPreview() {
        Row fullest = rows.stream().limit(ColumnGuesser.HEADER_ROWS)
                .reduce((a, b) -> b.filledCount() > a.filledCount() ? b : a).orElse(null);
        if (fullest == null) {
            return "";
        }
        List<String> values = fullest.cells().stream().filter(cell -> !cell.isEmpty()).toList();
        String shown = String.join(" · ", values.subList(0, Math.min(values.size(), 6)));
        return values.size() > 6 ? shown + " · …" : shown;
    }

    /** The number of columns of the widest row. */
    public int width() {
        return rows.stream().mapToInt(row -> row.cells().size()).max().orElse(0);
    }

    // number: the row's number in the file (1 = first line or spreadsheet row), for messages to the user.
    public record Row(int number, List<String> cells) implements Serializable {

        // "" for a cell past the end of the row.
        public String cell(int index) {
            return index >= 0 && index < cells.size() ? cells.get(index) : "";
        }

        public int filledCount() {
            return (int) cells.stream().filter(cell -> !cell.isEmpty()).count();
        }
    }
}
