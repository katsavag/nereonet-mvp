package com.nereonix.nereonet_mvp.core.vesseldata;

/**
 * How one VesselAPI call ended (P45). Messages: vessel.result.<name>.
 *   OK              2xx with a JSON body
 *   NOT_FOUND       404: the vessel, or a recent position / ETA report, is not known
 *   UNAUTHORIZED    401 or 403: the key is wrong, or the plan does not include the call
 *   RATE_LIMITED    429
 *   TIMEOUT         no answer within the timeouts
 *   PROVIDER_ERROR  5xx, no connection, any other status, or a body that is not JSON
 */
public enum VesselApiResult {

    OK, NOT_FOUND, UNAUTHORIZED, RATE_LIMITED, TIMEOUT, PROVIDER_ERROR;

    /** A failure the provider may get over (everything except OK and NOT_FOUND). */
    public boolean failed() {
        return this != OK && this != NOT_FOUND;
    }
}
