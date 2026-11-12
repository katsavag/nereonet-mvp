package com.nereonix.nereonet_mvp.core.importfile;

// What importing a row does (P24), shown on each row of the review screen. Labels: importFile.action.<NAME>.
public enum ImportAction {
    // A new record (for crew: an invitation).
    CREATE,
    // Changes a record that exists.
    UPDATE,
    // The record exists with the same values: skipped.
    UNCHANGED
}
