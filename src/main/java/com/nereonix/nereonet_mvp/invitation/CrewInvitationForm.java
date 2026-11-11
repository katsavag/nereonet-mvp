package com.nereonix.nereonet_mvp.invitation;

import com.nereonix.nereonet_mvp.core.user.Rank;
import com.nereonix.nereonet_mvp.core.user.Role;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// The ship comes from the URL, not from the form.
public class CrewInvitationForm {

    @NotBlank(message = "{invitation.error.fullNameRequired}")
    @Size(max = 255)
    private String fullName;

    @NotBlank(message = "{invitation.error.emailRequired}")
    @Email(message = "{invitation.error.emailInvalid}")
    @Size(max = 255)
    private String email;

    private String confirmEmail;

    @NotNull(message = "{invitation.error.roleRequired}")
    private Role role;

    private Rank rank;

    // Guards against typos: the link goes to whoever owns the address.
    @AssertTrue(message = "{invitation.error.emailMismatch}")
    public boolean isEmailConfirmed() {
        return InvitationForms.emailsMatch(email, confirmEmail);
    }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getConfirmEmail() { return confirmEmail; }
    public void setConfirmEmail(String confirmEmail) { this.confirmEmail = confirmEmail; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public Rank getRank() { return rank; }
    public void setRank(Rank rank) { this.rank = rank; }
}
