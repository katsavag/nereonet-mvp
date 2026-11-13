package com.nereonix.nereonet_mvp.requirement;

import com.nereonix.nereonet_mvp.core.certificate.Certificate;

/**
 * One document a seafarer needs (P42) and where they stand on it.
 * type         the certificate type as the requirement names it
 * optional     shown, never counted
 * note         the office's note ("needed before joining"), or null
 * state        Valid, Expiring, Expired or Missing
 * certificate  the certificate of that type that expires last, or null when missing
 */
public record RequiredDocument(String type, boolean optional, String note, RequirementState state,
                               Certificate certificate) {

    /** A required document that is missing or expired: counted as missing. */
    public boolean missing() {
        return !optional && state.needsAction();
    }
}
