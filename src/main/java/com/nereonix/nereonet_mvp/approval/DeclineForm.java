package com.nereonix.nereonet_mvp.approval;

import com.nereonix.nereonet_mvp.core.request.ItemRequest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// The "Decline" form of a request (P34), in a modal on /ship/approvals/{id}. The reason is required.
public class DeclineForm {

    @NotBlank(message = "{approval.decline.error.reasonRequired}")
    @Size(max = ItemRequest.REJECTION_REASON_MAX_LENGTH, message = "{approval.decline.error.reasonTooLong}")
    private String reason;

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason == null ? null : reason.strip(); }
}
