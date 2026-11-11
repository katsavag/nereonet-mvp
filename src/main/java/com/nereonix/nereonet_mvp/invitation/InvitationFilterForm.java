package com.nereonix.nereonet_mvp.invitation;

import com.nereonix.nereonet_mvp.core.invitation.Invitation;

// Filter of the office invitations page (GET). Null shows every invitation.
public class InvitationFilterForm {

    private Invitation.DisplayStatus status;

    public Invitation.DisplayStatus getStatus() { return status; }
    public void setStatus(Invitation.DisplayStatus status) { this.status = status; }
}
