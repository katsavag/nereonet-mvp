package com.nereonix.nereonet_mvp.core.speech;

/**
 * Speech to text failed (P57). The message names only what went wrong (the reason, a status code), never the key, the
 * audio or the text.
 */
public class SpeechToTextException extends RuntimeException {

    /** TIMEOUT: no text within app.assemblyai.timeout. PROVIDER_ERROR: anything else (a status, no connection, a
     *  transcript in error, an answer we cannot read, no key). */
    public enum Reason {
        TIMEOUT, PROVIDER_ERROR
    }

    private final Reason reason;

    public SpeechToTextException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
