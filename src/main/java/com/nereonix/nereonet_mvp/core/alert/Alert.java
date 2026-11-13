package com.nereonix.nereonet_mvp.core.alert;

/**
 * One alert as it stands right now (P43): what it is (its key) and how bad it is (its level, AlertLevels). What the
 * Overview, the Vetting list and the snooze sync compare the snoozes against.
 */
public record Alert(AlertKey key, int level) {

    public static Alert of(AlertKind kind, long subjectId, int level) {
        return new Alert(new AlertKey(kind, subjectId), level);
    }
}
