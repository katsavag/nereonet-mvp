package com.nereonix.nereonet_mvp.core.importfile;

import java.util.List;

// An uploaded table: the first non-empty row as the header, then the other non-empty rows.
// Every cell is a trimmed string ("" when empty).
public record TableFile(List<String> header, List<Row> rows) {

    /**
     * True if the header row has exactly these column names, in this order, ignoring case.
     * Empty cells after the last column are ignored: spreadsheets often have them.
     */
    public boolean hasHeader(List<String> expected) {
        int end = header.size();
        while (end > 0 && header.get(end - 1).isEmpty()) {
            end--;
        }
        if (end != expected.size()) {
            return false;
        }
        for (int i = 0; i < end; i++) {
            if (!header.get(i).equalsIgnoreCase(expected.get(i))) {
                return false;
            }
        }
        return true;
    }

    // number: the row's number in the file (1 = first line or spreadsheet row), for messages to the user.
    public record Row(int number, List<String> cells) {

        // "" for a cell past the end of the row.
        public String cell(int index) {
            return index < cells.size() ? cells.get(index) : "";
        }
    }
}
