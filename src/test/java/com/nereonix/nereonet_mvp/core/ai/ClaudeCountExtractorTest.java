package com.nereonix.nereonet_mvp.core.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nereonix.nereonet_mvp.core.ai.CountExtractor.Extraction;
import com.nereonix.nereonet_mvp.core.ai.CountExtractor.Item;
import com.nereonix.nereonet_mvp.core.ai.CountExtractor.Line;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real Claude client against a local HTTP server: POST /v1/messages with the key, the model, low effort, the
 * answer's JSON schema, the rules, and only the transcript and the items; the answer checked by CountAnswer; an answer
 * that is not JSON is nothing understood; a status, a refusal or a slow answer is a failure; the key never in a
 * failure or the settings' text.
 */
class ClaudeCountExtractorTest {

    private static final String KEY = "sk-ant-test-key-0123456789";
    private static final String MODEL = "claude-haiku-5-5";
    private static final List<Item> ITEMS = List.of(new Item(11L, "Tomatoes", "kg"), new Item(12L, "Eggs", "pcs"));
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String answer;
    private volatile long delayMillis;
    private ClaudeCountExtractor extractor;
    private ClaudeSettings settings;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " | "
                    + key(exchange.getRequestHeaders().getFirst("x-api-key"),
                            exchange.getRequestHeaders().getFirst("Authorization")) + " | "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(status, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } catch (IOException ignored) {
                // the client gave up (timeout test)
            }
        });
        server.start();
        settings = new ClaudeSettings("http://127.0.0.1:" + server.getAddress().getPort() + "/", KEY, MODEL,
                Duration.ofSeconds(2), 0, false);
        extractor = new ClaudeCountExtractor(settings);
        answer = message("{\"counts\": [{\"item_id\": 11, \"quantity\": 5, \"words\": \"five tomatoes\", "
                + "\"confidence\": 0.9}, {\"item_id\": 77, \"quantity\": 3, \"words\": \"three herrings\", "
                + "\"confidence\": 0.9}], \"unmatched\": [\"a box of something\"]}", "end_turn");
    }

    @AfterEach
    void stop() {
        extractor.close();
        server.stop(0);
    }

    @Test
    void sendsOnlyTheTextAndTheItemsAndChecksTheAnswer() {
        Extraction extraction = extractor.extract("five tomatoes, three herrings, a box of something", ITEMS);

        assertThat(extraction.lines()).containsExactly(new Line(11L, new BigDecimal("5"), "five tomatoes", 0.9));
        assertThat(extraction.unmatched()).containsExactly("three herrings", "a box of something");

        assertThat(requests).hasSize(1);
        String[] parts = requests.get(0).split(" \\| ", 3);
        assertThat(parts[0]).isEqualTo("POST /v1/messages");
        assertThat(parts[1]).isEqualTo(KEY);
        JsonNode body = MAPPER.readTree(parts[2]);
        assertThat(body.get("model").asString()).isEqualTo(MODEL);
        assertThat(body.has("temperature")).isFalse();
        assertThat(body.get("output_config").get("effort").asString()).isEqualTo("low");
        JsonNode format = body.get("output_config").get("format");
        assertThat(format.get("type").asString()).isEqualTo("json_schema");
        assertThat(format.get("schema").get("required").toString()).isEqualTo("[\"counts\",\"unmatched\"]");
        assertThat(body.get("system").asString())
                .contains("Never invent an item or an id", "Reply with JSON only", "the number as spoken");
        assertThat(body.get("messages")).hasSize(1);
        JsonNode message = body.get("messages").get(0);
        assertThat(message.get("role").asString()).isEqualTo("user");
        JsonNode data = MAPPER.readTree(message.get("content").asString());
        assertThat(data.propertyNames()).containsExactly("items", "transcript");
        assertThat(data.get("transcript").asString()).isEqualTo("five tomatoes, three herrings, a box of something");
        assertThat(data.get("items").toString())
                .isEqualTo("[{\"id\":11,\"name\":\"Tomatoes\",\"unit\":\"kg\"},{\"id\":12,\"name\":\"Eggs\",\"unit\":\"pcs\"}]");
    }

    @Test
    void anAnswerThatIsNotJsonIsNothingUnderstood() {
        answer = message("Here you go: five tomatoes", "end_turn");
        assertThat(extractor.extract("five tomatoes", ITEMS).isEmpty()).isTrue();

        answer = message("{\"counts\": [{\"item_id\": 11, \"quan", "max_tokens");
        assertThat(extractor.extract("five tomatoes", ITEMS).isEmpty()).isTrue();
    }

    @Test
    void aStatusARefusalOrASlowAnswerIsAFailureWithoutTheKey() {
        for (int code : new int[] {401, 400, 429, 500, 529}) {
            status = code;
            answer = "{\"type\": \"error\", \"error\": {\"type\": \"api_error\", \"message\": \"nope\"}}";
            assertThatThrownBy(() -> extractor.extract("five tomatoes", ITEMS))
                    .isInstanceOfSatisfying(CountExtractionException.class, e -> {
                        assertThat(e.reason()).isEqualTo(CountExtractionException.Reason.PROVIDER_ERROR);
                        assertThat(e.getMessage()).contains(String.valueOf(code)).doesNotContain(KEY);
                    });
        }
        status = 200;
        answer = message("", "refusal");
        assertThatThrownBy(() -> extractor.extract("five tomatoes", ITEMS))
                .isInstanceOfSatisfying(CountExtractionException.class, e -> {
                    assertThat(e.reason()).isEqualTo(CountExtractionException.Reason.PROVIDER_ERROR);
                    assertThat(e.getMessage()).contains("refused");
                });

        answer = message("{\"counts\": [], \"unmatched\": []}", "end_turn");
        delayMillis = 3000;
        assertThatThrownBy(() -> extractor.extract("five tomatoes", ITEMS))
                .isInstanceOfSatisfying(CountExtractionException.class,
                        e -> assertThat(e.reason()).isEqualTo(CountExtractionException.Reason.TIMEOUT));
        assertThat(settings.toString()).doesNotContain(KEY).contains("key set");
    }

    @Test
    void noTextOrNoItemsIsNotSent() {
        assertThat(extractor.extract("  ", ITEMS).isEmpty()).isTrue();
        assertThat(extractor.extract("five tomatoes", List.of()).isEmpty()).isTrue();
        assertThat(requests).isEmpty();
    }

    @Test
    void theDefaultModelIsHaiku() {
        ClaudeSettings defaults = new ClaudeSettings("", "", "", Duration.ZERO, 1, false);
        assertThat(defaults.model()).isEqualTo("claude-haiku-5-5");
        assertThat(defaults.baseUrl()).isEqualTo("https://api.anthropic.com");
        assertThat(defaults.configured()).isFalse();
    }

    // The key as sent: the SDK puts it in x-api-key or in Authorization as a Bearer token.
    private static String key(String apiKey, String authorization) {
        if (apiKey != null) {
            return apiKey;
        }
        return authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
    }

    // A Messages API answer with one text block (none when the text is empty).
    private static String message(String text, String stopReason) {
        String content = text.isEmpty() ? "[]"
                : "[{\"type\": \"text\", \"text\": " + MAPPER.writeValueAsString(text) + "}]";
        return "{\"id\": \"msg_test\", \"type\": \"message\", \"role\": \"assistant\", \"model\": \"" + MODEL + "\", "
                + "\"content\": " + content + ", \"stop_reason\": \"" + stopReason + "\", \"stop_sequence\": null, "
                + "\"usage\": {\"input_tokens\": 1, \"output_tokens\": 1}}";
    }
}
