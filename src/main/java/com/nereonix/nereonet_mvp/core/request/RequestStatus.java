package com.nereonix.nereonet_mvp.core.request;

// Labels: request.status.<NAME> in messages.properties (crew screens).
public enum RequestStatus {
    // Submitted, waiting for the Master of the ship (RequestService.submit).
    PENDING,
    APPROVED,
    REJECTED,
    // Withdrawn by the person who asked, before the Master decided.
    CANCELLED;

    public String labelKey() {
        return "request.status." + name();
    }
}
