package com.nereonix.nereonet_mvp.invitation;

// Shared check for the invitation forms.
final class InvitationForms {

    private InvitationForms() {
    }

    // Case-insensitive. A blank email is reported by @NotBlank, not here.
    static boolean emailsMatch(String email, String confirmEmail) {
        if (email == null || email.isBlank()) {
            return true;
        }
        return confirmEmail != null && email.trim().equalsIgnoreCase(confirmEmail.trim());
    }
}
