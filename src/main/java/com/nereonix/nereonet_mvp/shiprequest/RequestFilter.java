package com.nereonix.nereonet_mvp.shiprequest;

import java.util.Locale;
import java.util.Set;

import com.nereonix.nereonet_mvp.core.request.RequestStatus;

// The filter of /ship/requests, chosen with ?status=waiting|approved|declined; anything else is All. Waiting:
// PENDING. Approved: APPROVED. Declined: REJECTED. A cancelled request is under All only.
public enum RequestFilter {
    ALL(Set.of(RequestStatus.values())),
    WAITING(Set.of(RequestStatus.PENDING)),
    APPROVED(Set.of(RequestStatus.APPROVED)),
    DECLINED(Set.of(RequestStatus.REJECTED));

    private final Set<RequestStatus> statuses;

    RequestFilter(Set<RequestStatus> statuses) {
        this.statuses = statuses;
    }

    public Set<RequestStatus> statuses() {
        return statuses;
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
