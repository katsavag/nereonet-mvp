package com.nereonix.nereonet_mvp.core.form;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

/**
 * A new password and its repetition, with the rules every new password follows (registration, password reset, P14).
 * On the page: layout/parts :: newPasswordFields, which shows the same rules as a checklist.
 */
public abstract class NewPasswordForm {

    // BCrypt uses at most 72 bytes.
    @Size(min = 8, max = 72, message = "{password.error.length}")
    private String password;

    private String confirmPassword;

    @AssertTrue(message = "{password.error.mismatch}")
    public boolean isPasswordConfirmed() {
        return password != null && password.equals(confirmPassword);
    }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getConfirmPassword() { return confirmPassword; }
    public void setConfirmPassword(String confirmPassword) { this.confirmPassword = confirmPassword; }
}
