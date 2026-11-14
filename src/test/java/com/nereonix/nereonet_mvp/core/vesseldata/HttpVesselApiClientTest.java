package com.nereonix.nereonet_mvp.core.vesseldata;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nereonix.nereonet_mvp.core.vesseldata.VesselApiClient.Fetch;
import com.sun.net.httpserver.HttpServer;

/**
 * P45: the real client against a local HTTP server: the three paths with filter.idType=imo, Accept and the Bearer
 * key, each status mapped to its result, a body that is not JSON, the 5 s read timeout, and the key never in a
 * result or in the settings' text.
 */
class HttpVesselApiClientTest {

    private static final String KEY = "test-key-0123456789";

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private volatile long delayMillis;
    private HttpVesselApiClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " | "
                    + exchange.getRequestHeaders().getFirst("Accept") + " | "
                    + exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = bodies.getOrDefault(path, "{\"ok\": true}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statuses.getOrDefault(path, 200), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            } catch (IOException ignored) {
                // the client gave up (timeout test)
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
        client = new HttpVesselApiClient(new VesselApiSettings(base, KEY, false));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void theThreeCallsSendTheKeyAsBearerAndKeepTheBodyAsReceived() {
        bodies.put("/v1/vessel/9328716", "{\"vessel\": {\"name\": \"A\", \"brand_new_field\": [1, 2]}}");
        Fetch particulars = client.fetch(VesselEndpoint.PARTICULARS, "9328716");
        client.fetch(VesselEndpoint.POSITION, "9328716");
        client.fetch(VesselEndpoint.ETA, "9328716");

        assertThat(particulars.ok()).isTrue();
        assertThat(particulars.rawJson()).isEqualTo("{\"vessel\": {\"name\": \"A\", \"brand_new_field\": [1, 2]}}");
        assertThat(requests).containsExactly(
                "GET /v1/vessel/9328716?filter.idType=imo | application/json | Bearer " + KEY,
                "GET /v1/vessel/9328716/position?filter.idType=imo | application/json | Bearer " + KEY,
                "GET /v1/vessel/9328716/eta?filter.idType=imo | application/json | Bearer " + KEY);
    }

    @Test
    void eachStatusHasItsResultAndTheKeyIsInNone() {
        Map<Integer, VesselApiResult> expected = Map.of(404, VesselApiResult.NOT_FOUND,
                401, VesselApiResult.UNAUTHORIZED, 403, VesselApiResult.UNAUTHORIZED,
                429, VesselApiResult.RATE_LIMITED, 500, VesselApiResult.PROVIDER_ERROR,
                503, VesselApiResult.PROVIDER_ERROR, 504, VesselApiResult.TIMEOUT);
        expected.forEach((status, result) -> {
            statuses.put("/v1/vessel/9328716/eta", status);
            bodies.put("/v1/vessel/9328716/eta", "{\"error\": {\"message\": \"no\"}}");
            Fetch fetch = client.fetch(VesselEndpoint.ETA, "9328716");
            assertThat(fetch.result()).as("status " + status).isEqualTo(result);
            assertThat(fetch.rawJson()).isNull();
        });

        statuses.clear();
        bodies.put("/v1/vessel/9328716/eta", "<html>not json</html>");
        assertThat(client.fetch(VesselEndpoint.ETA, "9328716").result()).isEqualTo(VesselApiResult.PROVIDER_ERROR);

        assertThat(new VesselApiSettings("", KEY, false).toString()).doesNotContain(KEY).contains("key set");
        assertThat(new VesselApiSettings("", KEY, false).baseUrl()).isEqualTo("https://api.vesselapi.com/v1");
    }

    @Test
    void noAnswerWithinTheReadTimeoutIsATimeout() {
        delayMillis = HttpVesselApiClient.TIMEOUT.toMillis() + 1_500;
        long started = System.nanoTime();
        assertThat(client.fetch(VesselEndpoint.POSITION, "9328716").result()).isEqualTo(VesselApiResult.TIMEOUT);
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(HttpVesselApiClient.TIMEOUT.toMillis() + 1_400);
    }

    @Test
    void withoutAKeyOrWithABadNumberNothingIsSent() {
        HttpVesselApiClient off = new HttpVesselApiClient(new VesselApiSettings(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "", false));
        assertThat(off.fetch(VesselEndpoint.PARTICULARS, "9328716").ok()).isFalse();
        assertThat(client.fetch(VesselEndpoint.PARTICULARS, "93287/../x").ok()).isFalse();
        assertThat(requests).isEmpty();
    }
}
