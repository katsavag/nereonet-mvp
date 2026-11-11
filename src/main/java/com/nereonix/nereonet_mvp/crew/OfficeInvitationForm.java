package com.nereonix.nereonet_mvp.crew;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// "Invite office user" on the Office users page.
public class OfficeInvitationForm {

    @NotBlank(message = "{invitation.error.fullNameRequired}")
    @Size(max = 255)
    private String fullName;

    @NotBlank(message = "{invitation.error.emailRequired}")
    @Email(message = "{invitation.error.emailInvalid}")
    @Size(max = 255)
    private String email;

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
