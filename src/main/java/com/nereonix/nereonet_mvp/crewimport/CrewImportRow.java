package com.nereonix.nereonet_mvp.crewimport;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

// One crew member on the review screen. Values stay strings so a wrong value from the file can be shown.
// role, rank and shipImo hold the canonical value (e.g. "CHIEF_COOK", "SECOND_OFFICER", "9074729")
// when the file value was recognised, otherwise the value as it was in the file.
public class CrewImportRow implements Serializable {

    // The row's number in the file. Not editable.
    private int rowNumber;

    // The annotations are only used by CrewImportService.validate for the length and email format checks.
    @Size(max = 255)
    private String fullName = "";

    @Email
    @Size(max = 255)
    private String email = "";

    private String rank = "";
    private String role = "";
    private String shipImo = "";
    private boolean excluded;

    // Field name -> message key. Set by CrewImportService.validate; empty for excluded rows.
    private final Map<String, String> errors = new HashMap<>();

    public boolean hasErrors() { return !errors.isEmpty(); }

    public int getRowNumber() { return rowNumber; }
    public void setRowNumber(int rowNumber) { this.rowNumber = rowNumber; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName == null ? "" : fullName.strip(); }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email == null ? "" : email.strip(); }

    public String getRank() { return rank; }
    public void setRank(String rank) { this.rank = rank == null ? "" : rank.strip(); }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role == null ? "" : role.strip(); }

    public String getShipImo() { return shipImo; }
    public void setShipImo(String shipImo) { this.shipImo = shipImo == null ? "" : shipImo.strip(); }

    public boolean isExcluded() { return excluded; }
    public void setExcluded(boolean excluded) { this.excluded = excluded; }

    public Map<String, String> getErrors() { return errors; }
}
