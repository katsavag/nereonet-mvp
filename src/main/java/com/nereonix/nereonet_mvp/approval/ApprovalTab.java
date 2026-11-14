package com.nereonix.nereonet_mvp.approval;

import java.util.Locale;

import com.nereonix.nereonet_mvp.core.request.RequestStatus;

// The three tabs of /ship/approvals (P33, P48), chosen with ?tab=pending|approved|declined; anything else is Pending.
// The old ?tab=decided is a 301 to Approved (ApprovalController).
public enum ApprovalTab {
    PENDING(RequestStatus.PENDING), APPROVED(RequestStatus.APPROVED), DECLINED(RequestStatus.REJECTED);

    private final RequestStatus status;

    ApprovalTab(RequestStatus status) {
        this.status = status;
    }

    public RequestStatus status() {
        return status;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    static ApprovalTab of(String value) {
        for (ApprovalTab tab : values()) {
            if (tab.key().equals(value)) {
                return tab;
            }
        }
        return PENDING;
    }
}
