package com.nereonix.nereonet_mvp.crewimport;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

// The review screen: every crew member from the uploaded file. Kept in the HTTP session until the import
// is submitted, and the edited values are bound onto it (see CrewImportController).
public class CrewImportForm implements Serializable {

    private List<CrewImportRow> rows = new ArrayList<>();

    public int getErrorCount() {
        return (int) rows.stream().filter(row -> !row.isExcluded() && row.hasErrors()).count();
    }

    public int getExcludedCount() {
        return (int) rows.stream().filter(CrewImportRow::isExcluded).count();
    }

    public List<CrewImportRow> getIncludedRows() {
        return rows.stream().filter(row -> !row.isExcluded()).toList();
    }

    // Valid after CrewImportService.validate: something to import and no errors.
    public boolean isReady() {
        return getErrorCount() == 0 && !getIncludedRows().isEmpty();
    }

    public List<CrewImportRow> getRows() { return rows; }
    public void setRows(List<CrewImportRow> rows) { this.rows = rows; }
}
