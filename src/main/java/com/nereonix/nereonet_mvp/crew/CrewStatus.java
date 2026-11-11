package com.nereonix.nereonet_mvp.crew;

import java.util.Arrays;
import java.util.Optional;

/**
 * The Status filter of the Crew list (P17), sent as ?status=key so other pages (the Overview) can link to it:
 * /office/crew?status=expired. Labels: crew.filter.status.<key> in messages.properties.
 * ACTIVE and INACTIVE are users (enabled or not); INVITED and EXPIRED are PENDING invitations before and after their
 * expiry date.
 */
public enum CrewStatus {

    ACTIVE("active"),
    INVITED("invited"),
    EXPIRED("expired"),
    INACTIVE("inactive");

    private final String key;

    CrewStatus(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** The status with this key; empty for null or anything else, so an unknown value shows the whole list. */
    public static Optional<CrewStatus> of(String key) {
        return Arrays.stream(values()).filter(status -> status.key.equals(key)).findFirst();
    }

    boolean users() {
        return this == ACTIVE || this == INACTIVE;
    }
}
