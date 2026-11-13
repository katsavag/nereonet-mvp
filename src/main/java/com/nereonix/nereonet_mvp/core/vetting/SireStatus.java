package com.nereonix.nereonet_mvp.core.vetting;

/**
 * Where a SIRE 2.0 report stands (P41), as the office follows it: the draft has come in (the operator may comment
 * until the deadline, SireSettings.commentDays), the operator's comments were sent, or the report is published.
 * Entered by hand; nothing here comes from OCIMF.
 */
public enum SireStatus {
    DRAFT_RECEIVED, COMMENTS_SENT, PUBLISHED;

    public String messageKey() {
        return "sire.status." + name();
    }
}
