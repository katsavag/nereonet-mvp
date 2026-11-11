package com.nereonix.nereonet_mvp.passwordreset;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

// The one field of /password/forgot (P14).
public class ForgotPasswordForm {

    @NotBlank(message = "{passwordReset.error.emailRequired}")
    @Email(message = "{passwordReset.error.emailInvalid}")
    private String email;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
