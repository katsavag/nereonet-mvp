package com.nereonix.nereonet_mvp.core.vetting;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * The RightShip API, read only (P40), called by the server only (RightShipSyncService), never from the browser, and
 * only when it is configured (RightShipSettings). HttpRightShipClient is the real one; the tests use a fake.
 * Every method throws RightShipException when the call fails (no connection, an error status, an answer it cannot
 * read).
 */
public interface RightShipClient {

    /** GET /vessel/v2/updates?SinceDate=yyyy-MM-dd: the IMO numbers of the vessels whose data changed since then. */
    List<String> updatedSince(LocalDate sinceDate);

    /**
     * POST /vessel/v2/info for these IMO numbers: what RightShip has on each (the overall Safety Score, the DOC
     * subscore, GHG Rating, inspection and restrictions; not the other five subscores). A vessel RightShip does not
     * know is left out.
     */
    List<VesselInfo> info(Collection<String> imoNumbers);

    /** One vessel as the API gives it. Null where RightShip has no value. */
    record VesselInfo(String imoNumber, Integer safetyScore, boolean indicative, LocalDate calculatedAt,
                      Integer docSafetyScore, GhgRating ghgRating, boolean ghgPlus, boolean ghgVerified,
                      LocalDate ghgRatedAt, Boolean inspectionRequired, InspectionOutcome lastInspectionOutcome,
                      LocalDate inspectionValidUntil, boolean restricted, String restrictionComments,
                      String platformLink) {
    }

    /** A call to RightShip failed. The message never holds the credentials. */
    class RightShipException extends RuntimeException {

        public RightShipException(String message) {
            super(message);
        }

        public RightShipException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
