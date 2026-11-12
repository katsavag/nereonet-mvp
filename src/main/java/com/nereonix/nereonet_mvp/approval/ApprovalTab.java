package com.nereonix.nereonet_mvp.approval;

import java.util.Locale;

// The two tabs of /ship/approvals (P33), chosen with ?tab=waiting|decided; anything else is Waiting.
public enum ApprovalTab {
    WAITING, DECIDED;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    static ApprovalTab of(String value) {
        return "decided".equals(value) ? DECIDED : WAITING;
    }
}
