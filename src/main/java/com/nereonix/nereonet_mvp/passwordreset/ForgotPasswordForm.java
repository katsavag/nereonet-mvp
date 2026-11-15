package com.nereonix.nereonet_mvp.passwordreset;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// The one field of /password/forgot (P14).
public class ForgotPasswordForm {

    @NotBlank(message = "{passwordReset.error.emailRequired}")
    @Email(message = "{passwordReset.error.emailInvalid}")
    // P60: as long as a user's email can be (User.email, 255).
    @Size(max = 255, message = "{passwordReset.error.emailInvalid}")
    private String email;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
