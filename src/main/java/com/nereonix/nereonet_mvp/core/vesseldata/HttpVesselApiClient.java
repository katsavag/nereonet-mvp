package com.nereonix.nereonet_mvp.core.vesseldata;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The real VesselAPI client (P45): GET with Accept: application/json and Authorization: Bearer &lt;key&gt;, 5 s to
 * connect and 5 s to read. The body is kept as received (VesselDataSnapshot.rawJson); this class only checks it is a
 * JSON object of a sane size. Logs say which call ended how, never the key, the headers or the body.
 * Active unless app.vesselapi.fake is true.
 */
@Component
@ConditionalOnProperty(name = "app.vesselapi.fake", havingValue = "false", matchIfMissing = true)
public class HttpVesselApiClient implements VesselApiClient {

    private static final Logger log = LoggerFactory.getLogger(HttpVesselApiClient.class);

    static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final VesselApiSettings settings;
    private final RestClient http;

    public HttpVesselApiClient(VesselApiSettings settings) {
        this.settings = settings;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        factory.setReadTimeout(TIMEOUT);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public Fetch fetch(VesselEndpoint endpoint, String imo) {
        if (!settings.configured() || imo == null || !imo.matches("[0-9]{7}")) {
            return Fetch.failed(VesselApiResult.PROVIDER_ERROR);
        }
        Fetch fetch;
        try {
            fetch = http.get()
                    .uri(settings.baseUrl() + endpoint.path(imo))
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> h.setBearerAuth(settings.apiKey()))
                    .exchange((request, response) -> answer(response.getStatusCode(), response.getBody()));
        } catch (RuntimeException e) {
            fetch = Fetch.failed(timedOut(e) ? VesselApiResult.TIMEOUT : VesselApiResult.PROVIDER_ERROR);
            log.warn("VesselAPI {} for IMO {}: {} ({})", endpoint, imo, fetch.result(), e.getClass().getSimpleName());
            return fetch;
        }
        if (!fetch.ok()) {
            log.warn("VesselAPI {} for IMO {}: {}", endpoint, imo, fetch.result());
        }
        return fetch;
    }

    // The status decides; only a 2xx body is read and kept.
    static Fetch answer(HttpStatusCode status, InputStream body) throws IOException {
        if (status.is2xxSuccessful()) {
            byte[] bytes = body.readNBytes(VesselDataSnapshot.RAW_JSON_MAX_LENGTH + 1);
            String json = new String(bytes, StandardCharsets.UTF_8);
            return json.length() <= VesselDataSnapshot.RAW_JSON_MAX_LENGTH && VesselJson.isObject(json)
                    ? Fetch.ok(json) : Fetch.failed(VesselApiResult.PROVIDER_ERROR);
        }
        return Fetch.failed(switch (status.value()) {
            case 404 -> VesselApiResult.NOT_FOUND;
            case 401, 403 -> VesselApiResult.UNAUTHORIZED;
            case 429 -> VesselApiResult.RATE_LIMITED;
            case 408, 504 -> VesselApiResult.TIMEOUT;
            default -> VesselApiResult.PROVIDER_ERROR;
        });
    }

    private static boolean timedOut(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
