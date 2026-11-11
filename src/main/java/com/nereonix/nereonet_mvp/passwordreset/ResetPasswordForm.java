package com.nereonix.nereonet_mvp.passwordreset;

import com.nereonix.nereonet_mvp.core.form.NewPasswordForm;

// /password/reset (P14): the token from the email link, and the new password with the rules of NewPasswordForm.
public class ResetPasswordForm extends NewPasswordForm {

    private String token;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
}
