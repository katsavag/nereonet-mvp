package com.nereonix.nereonet_mvp.registration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

// Email, full name, role and ship come from the invitation, not from the form.
public class RegistrationForm {

    private String token;

    // BCrypt uses at most 72 bytes.
    @Size(min = 8, max = 72, message = "{registration.error.passwordLength}")
    private String password;

    private String confirmPassword;

    @AssertTrue(message = "{registration.error.passwordMismatch}")
    public boolean isPasswordConfirmed() {
        return password != null && password.equals(confirmPassword);
    }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getConfirmPassword() { return confirmPassword; }
    public void setConfirmPassword(String confirmPassword) { this.confirmPassword = confirmPassword; }
}
