package com.nereonix.nereonet_mvp.shiprequest;

import java.util.Locale;
import java.util.Set;

import com.nereonix.nereonet_mvp.core.request.RequestStatus;

// The filter of /ship/requests, chosen with ?status=waiting|decided; anything else is All. Waiting: PENDING.
// Decided: no longer waiting, whether the Master approved or declined it or the person who asked cancelled it.
public enum RequestFilter {
    ALL(Set.of(RequestStatus.values())),
    WAITING(Set.of(RequestStatus.PENDING)),
    DECIDED(Set.of(RequestStatus.APPROVED, RequestStatus.REJECTED, RequestStatus.CANCELLED));

    private final Set<RequestStatus> statuses;

    RequestFilter(Set<RequestStatus> statuses) {
        this.statuses = statuses;
    }

    public boolean matches(RequestStatus status) {
        return statuses.contains(status);
    }

    /** The value of ?status=, or null for All. */
    public String key() {
        return this == ALL ? null : name().toLowerCase(Locale.ROOT);
    }

    static RequestFilter of(String value) {
        for (RequestFilter filter : values()) {
            if (filter != ALL && filter.key().equals(value)) {
                return filter;
            }
        }
        return ALL;
    }
}
