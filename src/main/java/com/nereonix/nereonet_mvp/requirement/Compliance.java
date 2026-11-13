package com.nereonix.nereonet_mvp.requirement;

import java.util.List;

import com.nereonix.nereonet_mvp.core.user.User;

/**
 * Where one seafarer stands on the documents the office requires for their rank on their vessel's type (P42).
 * documents: required ones first, then optional ones; each group missing, expired, expiring, valid, then by type.
 * Empty for a person without a rank or a vessel, or whose rank nothing applies to.
 * The totals count required documents only, an expired one as missing: "5 of 7" is held() of required().
 */
public record Compliance(User user, List<RequiredDocument> documents) {

    /** Any document applies, required or optional. */
    public boolean hasRequirements() {
        return !documents.isEmpty();
    }

    public long required() {
        return documents.stream().filter(d -> !d.optional()).count();
    }

    /** Required documents held and not expired (valid or expiring). */
    public long held() {
        return required() - missing();
    }

    /** Required documents missing or expired. */
    public long missing() {
        return documents.stream().filter(RequiredDocument::missing).count();
    }

    /** Required documents missing or expired, most urgent first: what the crew are asked to see the office about. */
    public List<RequiredDocument> missingDocuments() {
        return documents.stream().filter(RequiredDocument::missing).toList();
    }

    /**
     * The state the Crew list's icon shows: Missing (alert) while a required document is missing or expired, Expiring
     * (clock) while one expires within 30 days, else Valid (tick); null without required documents.
     */
    public RequirementState summary() {
        if (required() == 0) {
            return null;
        }
        if (missing() > 0) {
            return RequirementState.MISSING;
        }
        return documents.stream().anyMatch(d -> !d.optional() && d.state() == RequirementState.EXPIRING)
                ? RequirementState.EXPIRING : RequirementState.VALID;
    }
}
