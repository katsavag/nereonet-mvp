package com.nereonix.nereonet_mvp.core.importfile;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

// An import in progress (P24), kept in the HTTP session from the upload until the import is done or cancelled: the
// uploaded file with its mapping, and the review screen's rows (built once the mapping is confirmed). The review form
// binds the edited values onto the rows.
public abstract class ImportForm<R extends ImportRow> implements Serializable {

    /** The review screen's row filters: every row, the included rows with errors, the excluded rows. */
    public static final String SHOW_ERRORS = "errors";
    public static final String SHOW_EXCLUDED = "excluded";

    // Null before an upload (or after the session lost it).
    private ImportUpload upload;
    private List<R> rows = new ArrayList<>();
    // True once the review rows were built from the confirmed mapping.
    private boolean built;

    public ImportUpload getUpload() { return upload; }

    /** Starts over with a new file. */
    public void setUpload(ImportUpload upload) {
        this.upload = upload;
        clearRows();
    }

    /** The review rows, built from the confirmed mapping (ImportTarget.buildRows). */
    public void rowsBuilt(List<R> built) {
        rows = new ArrayList<>(built);
        this.built = true;
    }

    /** The mapping changes: the review rows are built again afterwards. */
    public void clearRows() {
        rows = new ArrayList<>();
        built = false;
    }

    public boolean isBuilt() {
        return built && upload != null;
    }

    public List<R> getRows() { return rows; }
    public void setRows(List<R> rows) { this.rows = rows; }

    public List<R> getIncludedRows() {
        return rows.stream().filter(row -> !row.isExcluded()).toList();
    }

    public int getErrorCount() {
        return (int) rows.stream().filter(row -> !row.isExcluded() && row.hasErrors()).count();
    }

    public int getExcludedCount() {
        return (int) rows.stream().filter(ImportRow::isExcluded).count();
    }

    /** Included rows that do this. */
    public List<R> rowsTo(ImportAction action) {
        return rows.stream().filter(row -> !row.isExcluded() && row.getAction() == action).toList();
    }

    public int getCreateCount() { return rowsTo(ImportAction.CREATE).size(); }
    public int getUpdateCount() { return rowsTo(ImportAction.UPDATE).size(); }
    public int getUnchangedCount() { return rowsTo(ImportAction.UNCHANGED).size(); }

    /** Valid after the validation: something to create or update and no errors. */
    public boolean isReady() {
        return getErrorCount() == 0 && getCreateCount() + getUpdateCount() > 0;
    }

    /** SHOW_ERRORS or SHOW_EXCLUDED, or null for every row. */
    public static String show(String show) {
        return SHOW_ERRORS.equals(show) || SHOW_EXCLUDED.equals(show) ? show : null;
    }
}
