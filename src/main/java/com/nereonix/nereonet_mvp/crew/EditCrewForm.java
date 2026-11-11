package com.nereonix.nereonet_mvp.crew;

import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;
import com.nereonix.nereonet_mvp.core.user.User;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// The email cannot be changed, so it is not part of this form.
public class EditCrewForm {

    @NotBlank(message = "{user.error.fullNameRequired}")
    @Size(max = 255)
    private String fullName;

    private Rank rank;

    @NotNull(message = "{user.error.roleRequired}")
    private Role role;

    @NotNull(message = "{user.error.shipRequired}")
    private Long shipId;

    static EditCrewForm of(User user) {
        EditCrewForm form = new EditCrewForm();
        form.setFullName(user.getFullName());
        form.setRank(user.getRank());
        form.setRole(user.getRole());
        // Inactive ships are not in the list, so the office has to pick another one.
        form.setShipId(user.getShip().isActive() ? user.getShip().getId() : null);
        return form;
    }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public Rank getRank() { return rank; }
    public void setRank(Rank rank) { this.rank = rank; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public Long getShipId() { return shipId; }
    public void setShipId(Long shipId) { this.shipId = shipId; }
}
