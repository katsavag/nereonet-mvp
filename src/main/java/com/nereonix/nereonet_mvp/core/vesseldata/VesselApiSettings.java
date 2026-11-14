package com.nereonix.nereonet_mvp.core.vesseldata;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The VesselAPI settings (P45), app.vesselapi.*:
 *   base-url  default https://api.vesselapi.com/v1 (no trailing slash)
 *   api-key   VESSELAPI_API_KEY, from the environment or the local .env (gitignored). Never logged, never in a page,
 *             an audit entry or an error message.
 *   fake      true: FakeVesselApiClient answers instead of VesselAPI (dev screens and tests), with no key needed.
 * Lookup and refresh exist only when configured(); otherwise the buttons are hidden and the app never calls VesselAPI.
 */
@Component
public class VesselApiSettings {

    public static final String DEFAULT_BASE_URL = "https://api.vesselapi.com/v1";

    private final String baseUrl;
    private final String apiKey;
    private final boolean fake;

    public VesselApiSettings(@Value("${app.vesselapi.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
                             @Value("${app.vesselapi.api-key:}") String apiKey,
                             @Value("${app.vesselapi.fake:false}") boolean fake) {
        String url = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.strip();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.fake = fake;
    }

    /** A key is set, or the fake answers: "Look up by IMO" and "Refresh from VesselAPI" are offered. */
    public boolean configured() {
        return fake || !apiKey.isEmpty();
    }

    public String baseUrl() {
        return baseUrl;
    }

    String apiKey() {
        return apiKey;
    }

    public boolean fake() {
        return fake;
    }

    // Never the key.
    @Override
    public String toString() {
        return "VesselApiSettings[baseUrl=" + baseUrl + ", key " + (apiKey.isEmpty() ? "not set" : "set")
                + (fake ? ", fake" : "") + "]";
    }
}
