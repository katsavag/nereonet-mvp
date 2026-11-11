package com.nereonix.nereonet_mvp.registration;

import com.nereonix.nereonet_mvp.core.form.NewPasswordForm;

// Email, full name, role and ship come from the invitation, not from the form. The password rules: NewPasswordForm.
public class RegistrationForm extends NewPasswordForm {

    private String token;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
}
