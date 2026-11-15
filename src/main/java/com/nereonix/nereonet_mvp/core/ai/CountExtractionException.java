package com.nereonix.nereonet_mvp.core.ai;

/**
 * Turning the text into count lines failed. The message names only what went wrong (the reason, a status
 * code), never the key, the text or the answer.
 */
public class CountExtractionException extends RuntimeException {

    /** TIMEOUT: no answer within app.claude.timeout. PROVIDER_ERROR: anything else (a status, no connection, no
     *  key). An answer that is not valid JSON is not a failure: it is "nothing understood". */
    public enum Reason {
        TIMEOUT, PROVIDER_ERROR
    }

    private final Reason reason;

    public CountExtractionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
