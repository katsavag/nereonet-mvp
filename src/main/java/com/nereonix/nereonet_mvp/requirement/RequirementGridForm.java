package com.nereonix.nereonet_mvp.requirement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.nereonix.nereonet_mvp.core.certificate.Certificate;
import com.nereonix.nereonet_mvp.core.requirement.DocumentRequirement;
import com.nereonix.nereonet_mvp.core.user.Rank;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The grid of one vessel as posted (per vessel, by its id in "vessel"): a plain form, so it saves without JavaScript. One row per certificate
 * type: rows[i].type (hidden), rows[i].ranks (a checkbox per rank, its value the Rank), rows[i].optional and
 * rows[i].note. An unticked checkbox sends nothing, so ranks is empty and optional false unless sent.
 */
public class RequirementGridForm {

    // The id of the vessel whose grid this is; Save comes back to it.
    private String vessel;

    @Valid
    private List<RowForm> rows = new ArrayList<>();

    public static class RowForm {

        @NotBlank
        @Size(max = Certificate.TYPE_MAX_LENGTH)
        private String type;

        private Set<Rank> ranks = new HashSet<>();

        // The row toggle, "Select all ranks for <type>": every rank of the row, also without JavaScript.
        private boolean all;

        private boolean optional;

        @Size(max = DocumentRequirement.NOTE_MAX_LENGTH, message = "{requirement.error.noteTooLong}")
        private String note;

        public String getType() { return type; }
        public void setType(String type) { this.type = type == null ? null : type.strip(); }

        public Set<Rank> getRanks() { return ranks; }
        public void setRanks(Set<Rank> ranks) { this.ranks = ranks == null ? new HashSet<>() : ranks; }

        public boolean isAll() { return all; }
        public void setAll(boolean all) { this.all = all; }

        public boolean isOptional() { return optional; }
        public void setOptional(boolean optional) { this.optional = optional; }

        public String getNote() { return note; }
        public void setNote(String note) { this.note = note == null || note.isBlank() ? null : note.strip(); }
    }

    public String getVessel() { return vessel; }
    public void setVessel(String vessel) { this.vessel = vessel; }

    public List<RowForm> getRows() { return rows; }
    public void setRows(List<RowForm> rows) { this.rows = rows; }
}
