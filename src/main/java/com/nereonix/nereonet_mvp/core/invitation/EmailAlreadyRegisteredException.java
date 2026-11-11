package com.nereonix.nereonet_mvp.core.invitation;

// Thrown by InvitationService.invite when a user with that email already exists.
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException() {
        super("A user with this email already exists");
    }
}
