package com.nereonix.nereonet_mvp.core.importfile;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

// One row of an import review screen (P24). The values are in the subclass, as strings, so a wrong value from the
// file can be shown and corrected.
public abstract class ImportRow implements Serializable {

    // The row's number in the file. Not editable.
    private int rowNumber;
    private boolean excluded;
    // Set by the validation; not editable.
    private ImportAction action = ImportAction.CREATE;

    // Field name -> message key. Set by the validation; empty for excluded rows.
    private final Map<String, String> errors = new HashMap<>();

    public boolean hasErrors() { return !errors.isEmpty(); }

    /** Whether the review screen shows the row with this filter (ImportForm.SHOW_ERRORS, SHOW_EXCLUDED, else all). */
    public boolean isShownFor(String show) {
        if (ImportForm.SHOW_ERRORS.equals(show)) {
            return !excluded && hasErrors();
        }
        if (ImportForm.SHOW_EXCLUDED.equals(show)) {
            return excluded;
        }
        return true;
    }

    public int getRowNumber() { return rowNumber; }
    public void setRowNumber(int rowNumber) { this.rowNumber = rowNumber; }

    public boolean isExcluded() { return excluded; }
    public void setExcluded(boolean excluded) { this.excluded = excluded; }

    public ImportAction getAction() { return action; }
    public void setAction(ImportAction action) { this.action = action; }

    public Map<String, String> getErrors() { return errors; }
}
