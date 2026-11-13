package com.nereonix.nereonet_mvp.core.vetting;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

/**
 * The state of the RightShip API sync (P40), one row (id 1): when the last run that worked started (the next run asks
 * for the updates since that day), and the last failure. A failure later than the last success is shown on the
 * Vetting list and on the RightShip pages ("Last sync failed at ... UTC").
 */
@Entity
@Getter
@Setter
public class RightShipSync {

    public static final long ID = 1L;
    public static final int ERROR_MAX_LENGTH = 500;

    @Id
    private Long id = ID;

    // When the last run that worked started; null before the first one.
    private Instant lastSuccessAt;

    private Instant lastFailureAt;

    // What went wrong, for the log; never shown to the office.
    @Column(length = ERROR_MAX_LENGTH)
    private String lastError;

    /** The last run failed: a failure, and no success since. */
    public boolean failing() {
        return lastFailureAt != null && (lastSuccessAt == null || lastFailureAt.isAfter(lastSuccessAt));
    }
}
