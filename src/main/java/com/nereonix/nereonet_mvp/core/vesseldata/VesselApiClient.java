package com.nereonix.nereonet_mvp.core.vesseldata;

/**
 * VesselAPI (P45), read only, called by the server only (VesselDataService), never from the browser. One method per
 * call, so one failing call never stops the others. HttpVesselApiClient is the real one; FakeVesselApiClient answers
 * in dev (app.vesselapi.fake) and in the tests.
 */
public interface VesselApiClient {

    /** GET endpoint for this IMO number (7 digits, validated). Never throws: a failure is in the result. */
    Fetch fetch(VesselEndpoint endpoint, String imo);

    /** What one call returned: the result and, for OK only, the body exactly as received (unknown fields kept). */
    record Fetch(VesselApiResult result, String rawJson) {

        public static Fetch ok(String rawJson) {
            return new Fetch(VesselApiResult.OK, rawJson);
        }

        public static Fetch failed(VesselApiResult result) {
            return new Fetch(result, null);
        }

        public boolean ok() {
            return result == VesselApiResult.OK;
        }
    }
}
