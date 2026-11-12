package com.nereonix.nereonet_mvp.crewimport;

import com.nereonix.nereonet_mvp.core.importfile.ImportForm;

// A crew import in progress (P24): the uploaded file with its mapping, then every crew member from it on the review
// screen. Kept in the HTTP session until the import is done or cancelled (see CrewImportController).
public class CrewImportForm extends ImportForm<CrewImportRow> {

    // P18: the file has a Role column (e.g. the template from before), which is ignored: the rank sets the role.
    // Set when the rows are built; not editable.
    private boolean roleColumnIgnored;

    public boolean isRoleColumnIgnored() { return roleColumnIgnored; }
    public void setRoleColumnIgnored(boolean roleColumnIgnored) { this.roleColumnIgnored = roleColumnIgnored; }
}
