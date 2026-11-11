package com.nereonix.nereonet_mvp.crewimport;

import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

// One crew member on the review screen. Values stay strings so a wrong value from the file can be shown.
// rank and shipImo hold the canonical value (e.g. "SECOND_OFFICER", "9074729") when the file value was recognised,
// otherwise the value as it was in the file. There is no role: it follows from the rank (getRole, P18).
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

    /** The role the rank sets (Rank.role()); null while the rank is empty or not one of the ranks. Not editable. */
    public Role getRole() {
        return Arrays.stream(Rank.values()).filter(r -> r.name().equals(rank)).findFirst().map(Rank::role)
                .orElse(null);
    }

    public String getShipImo() { return shipImo; }
    public void setShipImo(String shipImo) { this.shipImo = shipImo == null ? "" : shipImo.strip(); }

    public boolean isExcluded() { return excluded; }
    public void setExcluded(boolean excluded) { this.excluded = excluded; }

    public Map<String, String> getErrors() { return errors; }
}
