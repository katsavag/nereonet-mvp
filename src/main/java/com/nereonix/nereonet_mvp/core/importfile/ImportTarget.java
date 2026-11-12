package com.nereonix.nereonet_mvp.core.importfile;

import java.util.List;

// One kind of import (crew, items) as the shared steps (ImportSteps, P24) see it.
public interface ImportTarget<F extends ImportForm<?>> {

    /** "crew" or "item": saved mappings are per kind. */
    String kind();

    /** The fields the file's columns can be mapped to, in the order shown. */
    List<ImportField> fields();

    /** The template's column names: a file with exactly these, in this order, needs no mapping. */
    List<String> templateHeader();

    /** Builds the review rows from the form's upload and its confirmed mapping, replacing any rows. */
    void buildRows(F form);

    /** Sets each included row's action and errors; excluded rows get none. */
    void validate(F form);

    /**
     * Validates again, saves the included rows and the confirmed mapping (ImportMappingService.save), and records it
     * in the audit log. Throws IllegalStateException if the form is not ready.
     */
    ImportResult importRows(F form, String actorEmail);
}
