package com.nereonix.nereonet_mvp.invitation;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class OfficeInvitationForm {

    @NotBlank(message = "{invitation.error.fullNameRequired}")
    @Size(max = 255)
    private String fullName;

    @NotBlank(message = "{invitation.error.emailRequired}")
    @Email(message = "{invitation.error.emailInvalid}")
    @Size(max = 255)
    private String email;

    private String confirmEmail;

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
}
