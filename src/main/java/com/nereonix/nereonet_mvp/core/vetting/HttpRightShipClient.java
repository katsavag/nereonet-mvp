package com.nereonix.nereonet_mvp.core.vetting;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The real RightShip API client (P40): JSON over HTTPS with an OAuth 2 access token (client credentials, from
 * RightShipSettings.tokenUrl, kept until shortly before it expires). Short timeouts, as the sync runs at night and a
 * failure is simply reported. Field names are read leniently (camelCase as documented, a few spellings each), so a
 * renamed field shows as "not known" rather than failing the sync; the mapping is all in this class.
 * Only RightShipSyncService calls it, and only when RightShipSettings.configured().
 */
@Component
public class HttpRightShipClient implements RightShipClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final ParameterizedTypeReference<Object> JSON = new ParameterizedTypeReference<>() {
    };

    private final RightShipSettings settings;
    private final RestClient http;
    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;

    public HttpRightShipClient(RightShipSettings settings) {
        this.settings = settings;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        factory.setReadTimeout(TIMEOUT);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public List<String> updatedSince(LocalDate sinceDate) {
        Object body = call(() -> http.get()
                .uri(settings.baseUrl() + "/vessel/v2/updates?SinceDate={since}", sinceDate.toString())
                .headers(h -> h.setBearerAuth(token()))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve().body(JSON));
        List<String> imoNumbers = new ArrayList<>();
        for (Object element : elements(body)) {
            String imo = element instanceof Map<?, ?> map ? text(map, "imo", "imoNumber", "IMO") : text(element);
            if (imo != null) {
                imoNumbers.add(imo);
            }
        }
        return imoNumbers;
    }

    @Override
    public List<VesselInfo> info(Collection<String> imoNumbers) {
        Object body = call(() -> http.post()
                .uri(settings.baseUrl() + "/vessel/v2/info")
                .headers(h -> h.setBearerAuth(token()))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(Map.of("imos", List.copyOf(imoNumbers)))
                .retrieve().body(JSON));
        List<VesselInfo> vessels = new ArrayList<>();
        for (Object element : elements(body)) {
            if (element instanceof Map<?, ?> map && text(map, "imo", "imoNumber", "IMO") != null) {
                vessels.add(vessel(map));
            }
        }
        return vessels;
    }

    private static VesselInfo vessel(Map<?, ?> v) {
        String ghg = text(v, "ghgRating", "ghg", "ghgRatingValue");
        GhgRating rating = null;
        boolean plus = false;
        if (ghg != null && !ghg.isBlank()) {
            String letter = ghg.strip().substring(0, 1).toUpperCase(Locale.ROOT);
            rating = switch (letter) {
                case "A", "B", "C", "D", "E" -> GhgRating.valueOf(letter);
                default -> GhgRating.U;
            };
            plus = rating == GhgRating.A && ghg.contains("+");
        }
        String outcome = text(v, "lastInspectionOutcome", "inspectionOutcome");
        return new VesselInfo(
                text(v, "imo", "imoNumber", "IMO"),
                score(v.get(first(v, "safetyScore", "overallSafetyScore"))),
                bool(v, "indicative", "isIndicative", "indicativeScore"),
                date(text(v, "safetyScoreDate", "calculatedDate", "calculatedAt")),
                score(v.get(first(v, "docSafetyScore", "docScore"))),
                rating, plus || bool(v, "ghgPlus"),
                bool(v, "ghgVerified", "ghgRatingVerified", "isVerified"),
                date(text(v, "ghgRatingDate", "ghgRatedAt")),
                v.containsKey("inspectionRequired") ? bool(v, "inspectionRequired") : null,
                outcome == null ? null : outcome.toLowerCase(Locale.ROOT).startsWith("un")
                        ? InspectionOutcome.UNACCEPTABLE : InspectionOutcome.ACCEPTABLE,
                date(text(v, "inspectionValidUntil", "inspectionValidity", "inspectionExpiryDate")),
                bool(v, "restricted", "isRestricted", "hasRestrictions"),
                text(v, "restrictionComments", "restrictions"),
                text(v, "platformLink", "vesselUrl", "url"));
    }

    // An access token, fetched again shortly before it expires.
    private synchronized String token() {
        if (token != null && Instant.now().isBefore(tokenExpiresAt)) {
            return token;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", settings.clientId());
        form.add("client_secret", settings.clientSecret());
        Object body = call(() -> http.post().uri(settings.tokenUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().body(JSON));
        if (!(body instanceof Map<?, ?> map) || text(map, "access_token") == null) {
            throw new RightShipException("No access token in the answer of the token URL");
        }
        token = text(map, "access_token");
        long seconds;
        try {
            seconds = Long.parseLong(String.valueOf(map.get("expires_in")).strip());
        } catch (NumberFormatException e) {
            seconds = 300;
        }
        tokenExpiresAt = Instant.now().plusSeconds(Math.max(30, seconds - 60));
        return token;
    }

    private interface Call {
        Object run();
    }

    // Any failure of the call as a RightShipException, without the request (it may hold the secret).
    private static Object call(Call call) {
        try {
            return call.run();
        } catch (RestClientException | IllegalArgumentException e) {
            throw new RightShipException("RightShip call failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ", " + firstLine(e.getMessage())), e);
        }
    }

    // A list, or the list inside an object ({"data": [...]}).
    private static List<?> elements(Object body) {
        if (body instanceof List<?> list) {
            return list;
        }
        if (body instanceof Map<?, ?> map) {
            for (String key : List.of("data", "vessels", "items", "results", "updates")) {
                if (map.get(key) instanceof List<?> list) {
                    return list;
                }
            }
        }
        return List.of();
    }

    private static String first(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return key;
            }
        }
        return keys[0];
    }

    private static String text(Map<?, ?> map, String... keys) {
        return text(map.get(first(map, keys)));
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().strip();
        return text.isEmpty() ? null : text;
    }

    private static boolean bool(Map<?, ?> map, String... keys) {
        Object value = map.get(first(map, keys));
        return value instanceof Boolean b ? b : value != null && "true".equalsIgnoreCase(value.toString().strip());
    }

    // 0 to 5, or null for anything else ("N/A", empty).
    private static Integer score(Object value) {
        String text = text(value);
        if (text == null) {
            return null;
        }
        try {
            int score = (int) Double.parseDouble(text);
            return score >= 0 && score <= 5 ? score : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // "2026-10-21" or "2026-10-21T00:00:00Z"; null for anything else.
    private static LocalDate date(String text) {
        if (text == null) {
            return null;
        }
        try {
            return text.length() > 10 ? OffsetDateTime.parse(text).toLocalDate() : LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(text.substring(0, Math.min(10, text.length())));
            } catch (DateTimeParseException again) {
                return null;
            }
        }
    }

    private static String firstLine(String message) {
        int end = message.indexOf('\n');
        String line = end < 0 ? message : message.substring(0, end);
        return line.length() > 200 ? line.substring(0, 200) : line;
    }
}
