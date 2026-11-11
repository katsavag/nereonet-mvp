package com.nereonix.nereonet_mvp.crew;

import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// The "Add crew member" pop-up on the crew page. Sends an invitation.
public class AddCrewForm {

    @NotBlank(message = "{user.error.fullNameRequired}")
    @Size(max = 255)
    private String fullName;

    @NotBlank(message = "{invitation.error.emailRequired}")
    @Email(message = "{invitation.error.emailInvalid}")
    @Size(max = 255)
    private String email;

    private Rank rank;

    @NotNull(message = "{user.error.roleRequired}")
    private Role role;

    @NotNull(message = "{user.error.shipRequired}")
    private Long shipId;

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public Rank getRank() { return rank; }
    public void setRank(Rank rank) { this.rank = rank; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public Long getShipId() { return shipId; }
    public void setShipId(Long shipId) { this.shipId = shipId; }
}
