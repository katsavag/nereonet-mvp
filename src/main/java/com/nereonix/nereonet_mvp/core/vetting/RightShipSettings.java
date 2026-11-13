package com.nereonix.nereonet_mvp.core.vetting;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The RightShip API settings (P40), app.rightship.*: off by default. The sync and "Refresh from RightShip" exist only
 * when base-url, client-id and client-secret are all set; otherwise the app never calls RightShip.
 *   base-url       e.g. https://api.rightship.com (no trailing slash)
 *   client-id      the API client's id
 *   client-secret  its secret (never logged)
 *   token-url      where the client gets its access token (OAuth 2 client credentials); default base-url + /oauth/token
 */
@Component
public class RightShipSettings {

    private final String baseUrl;
    private final String clientId;
    private final String clientSecret;
    private final String tokenUrl;

    public RightShipSettings(@Value("${app.rightship.base-url:}") String baseUrl,
                             @Value("${app.rightship.client-id:}") String clientId,
                             @Value("${app.rightship.client-secret:}") String clientSecret,
                             @Value("${app.rightship.token-url:}") String tokenUrl) {
        this.baseUrl = strip(baseUrl);
        this.clientId = clientId == null ? "" : clientId.strip();
        this.clientSecret = clientSecret == null ? "" : clientSecret.strip();
        String token = strip(tokenUrl);
        this.tokenUrl = token.isEmpty() ? this.baseUrl + "/oauth/token" : token;
    }

    /** All three are set: the sync runs and "Refresh from RightShip" is offered. */
    public boolean configured() {
        return !baseUrl.isEmpty() && !clientId.isEmpty() && !clientSecret.isEmpty();
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String clientId() {
        return clientId;
    }

    public String clientSecret() {
        return clientSecret;
    }

    public String tokenUrl() {
        return tokenUrl;
    }

    private static String strip(String url) {
        String value = url == null ? "" : url.strip();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
