package com.nereonix.nereonet_mvp.core.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * P57: the real AssemblyAI client against a local HTTP server: the upload (raw bytes, the key as is), the transcript
 * request (audio_url, language_code en, keyterms_prompt), polling until completed, and the transcript deleted at the
 * end whatever happened; a transcript in error, a status and the timeout are failures without the key.
 */
class AssemblyAiSpeechClientTest {

    private static final String KEY = "assemblyai-test-key-0123456789";
    private static final byte[] AUDIO = {26, 69, -33, -93, 1, 2, 3};

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile byte[] uploaded;
    private volatile String submitted;
    private volatile int uploadStatus = 200;
    private volatile int pollsBeforeDone = 2;
    private volatile String finalStatus = "completed";
    private final AtomicInteger polls = new AtomicInteger();
    private AssemblyAiSettings settings;
    private AssemblyAiSpeechClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            requests.add(method + " " + path + " | " + exchange.getRequestHeaders().getFirst("Authorization") + " | "
                    + exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] in = exchange.getRequestBody().readAllBytes();
            int status = 200;
            String body;
            if (method.equals("POST") && path.equals("/v2/upload")) {
                uploaded = in;
                status = uploadStatus;
                body = "{\"upload_url\": \"https://cdn.assemblyai.com/upload/abc\"}";
            } else if (method.equals("POST") && path.equals("/v2/transcript")) {
                submitted = new String(in, StandardCharsets.UTF_8);
                body = "{\"id\": \"t-1\", \"status\": \"queued\"}";
            } else if (method.equals("GET") && path.equals("/v2/transcript/t-1")) {
                body = polls.incrementAndGet() < pollsBeforeDone ? "{\"id\": \"t-1\", \"status\": \"processing\"}"
                        : finalStatus.equals("completed")
                                ? "{\"id\": \"t-1\", \"status\": \"completed\", \"text\": \"Five tomatoes, thirty eggs.\"}"
                                : "{\"id\": \"t-1\", \"status\": \"error\", \"error\": \"Audio too short\"}";
            } else if (method.equals("DELETE") && path.equals("/v2/transcript/t-1")) {
                body = "{\"id\": \"t-1\", \"status\": \"completed\"}";
            } else {
                status = 404;
                body = "{}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        settings = new AssemblyAiSettings("http://127.0.0.1:" + server.getAddress().getPort() + "/", KEY,
                Duration.ofSeconds(5), "en", false);
        client = new AssemblyAiSpeechClient(settings, Duration.ofMillis(20));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void uploadsAsksForEnglishWithTheKeyTermsWaitsAndDeletes() {
        String text = client.transcribe(AUDIO, "audio/webm;codecs=opus", List.of("Tomatoes", "Eggs", "eggs", " ",
                "One two three four five six seven"));

        assertThat(text).isEqualTo("Five tomatoes, thirty eggs.");
        assertThat(uploaded).isEqualTo(AUDIO);
        assertThat(requests).containsExactly(
                "POST /v2/upload | " + KEY + " | application/octet-stream",
                "POST /v2/transcript | " + KEY + " | application/json",
                "GET /v2/transcript/t-1 | " + KEY + " | null",
                "GET /v2/transcript/t-1 | " + KEY + " | null",
                "DELETE /v2/transcript/t-1 | " + KEY + " | null");
        JsonNode body = JsonMapper.builder().build().readTree(submitted);
        assertThat(body.get("audio_url").asString()).isEqualTo("https://cdn.assemblyai.com/upload/abc");
        assertThat(body.get("language_code").asString()).isEqualTo("en");
        // Blank, a duplicate (ignoring case) and a phrase of more than 6 words are left out.
        assertThat(body.get("keyterms_prompt").toString()).isEqualTo("[\"Tomatoes\",\"Eggs\"]");
    }

    @Test
    void atMostTwoHundredKeyTerms() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            names.add("Item " + i);
        }
        assertThat(AssemblyAiSpeechClient.keyterms(names)).hasSize(200).startsWith("Item 0").endsWith("Item 199");
        assertThat(AssemblyAiSpeechClient.keyterms(List.of())).isEmpty();
    }

    @Test
    void aTranscriptInErrorFailsAndIsStillDeleted() {
        finalStatus = "error";
        assertThatThrownBy(() -> client.transcribe(AUDIO, "audio/webm", List.of()))
                .isInstanceOfSatisfying(SpeechToTextException.class, e -> {
                    assertThat(e.reason()).isEqualTo(SpeechToTextException.Reason.PROVIDER_ERROR);
                    assertThat(e.getMessage()).doesNotContain(KEY);
                });
        assertThat(requests.get(requests.size() - 1)).startsWith("DELETE /v2/transcript/t-1");
        assertThat(submitted).doesNotContain("keyterms_prompt");
    }

    @Test
    void noTextInTimeIsATimeoutAndIsStillDeleted() {
        pollsBeforeDone = Integer.MAX_VALUE;
        client = new AssemblyAiSpeechClient(new AssemblyAiSettings(settings.baseUrl(), KEY, Duration.ofMillis(300),
                "en", false), Duration.ofMillis(50));
        assertThatThrownBy(() -> client.transcribe(AUDIO, "audio/webm", List.of("Eggs")))
                .isInstanceOfSatisfying(SpeechToTextException.class,
                        e -> assertThat(e.reason()).isEqualTo(SpeechToTextException.Reason.TIMEOUT));
        assertThat(requests.get(requests.size() - 1)).startsWith("DELETE /v2/transcript/t-1");
    }

    @Test
    void aFailedUploadIsAProviderErrorWithoutTheKey() {
        for (int code : new int[] {401, 422, 500}) {
            uploadStatus = code;
            assertThatThrownBy(() -> client.transcribe(AUDIO, "audio/webm", List.of()))
                    .isInstanceOfSatisfying(SpeechToTextException.class, e -> {
                        assertThat(e.reason()).isEqualTo(SpeechToTextException.Reason.PROVIDER_ERROR);
                        assertThat(e.getMessage()).contains(String.valueOf(code)).doesNotContain(KEY);
                    });
        }
        // No transcript was made: nothing to delete, nothing else sent.
        assertThat(requests).allMatch(r -> r.startsWith("POST /v2/upload"));
        assertThat(settings.toString()).doesNotContain(KEY).contains("key set");
    }
}
