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
 * P57: the real Mistral client against a local HTTP server: POST /v1/chat/completions with the Bearer key, the model,
 * JSON mode, the rules, and only the transcript and the items; the answer checked by CountAnswer; an answer that is not
 * JSON is nothing understood; a status or a slow answer is a failure; the key never in a failure or the settings' text.
 */
class MistralCountExtractorTest {

    private static final String KEY = "mistral-test-key-0123456789";
    private static final List<Item> ITEMS = List.of(new Item(11L, "Tomatoes", "kg"), new Item(12L, "Eggs", "pcs"));

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String answer;
    private volatile long delayMillis;
    private MistralCountExtractor extractor;
    private MistralSettings settings;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " | "
                    + exchange.getRequestHeaders().getFirst("Authorization") + " | "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            } catch (IOException ignored) {
                // the client gave up (timeout test)
            }
        });
        server.start();
        settings = new MistralSettings("http://127.0.0.1:" + server.getAddress().getPort() + "/", KEY,
                "mistral-small-2603", Duration.ofSeconds(2), false);
        extractor = new MistralCountExtractor(settings);
        answer = completion("{\"counts\": [{\"item_id\": 11, \"quantity\": 5, \"words\": \"five tomatoes\", "
                + "\"confidence\": 0.9}, {\"item_id\": 77, \"quantity\": 3, \"words\": \"three herrings\", "
                + "\"confidence\": 0.9}], \"unmatched\": [\"a box of something\"]}");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sendsOnlyTheTextAndTheItemsAndChecksTheAnswer() {
        Extraction extraction = extractor.extract("five tomatoes, three herrings, a box of something", ITEMS);

        assertThat(extraction.lines()).containsExactly(new Line(11L, new BigDecimal("5"), "five tomatoes", 0.9));
        assertThat(extraction.unmatched()).containsExactly("three herrings", "a box of something");

        assertThat(requests).hasSize(1);
        String[] parts = requests.get(0).split(" \\| ", 3);
        assertThat(parts[0]).isEqualTo("POST /v1/chat/completions");
        assertThat(parts[1]).isEqualTo("Bearer " + KEY);
        JsonNode body = JsonMapper.builder().build().readTree(parts[2]);
        assertThat(body.get("model").asString()).isEqualTo("mistral-small-2603");
        assertThat(body.get("response_format").get("type").asString()).isEqualTo("json_object");
        assertThat(body.get("temperature").asInt()).isZero();
        assertThat(body.get("messages")).hasSize(2);
        assertThat(body.get("messages").get(0).get("role").asString()).isEqualTo("system");
        assertThat(body.get("messages").get(0).get("content").asString())
                .contains("Never invent an item or an id", "Reply with JSON only", "the number as spoken");
        assertThat(body.get("messages").get(1).get("role").asString()).isEqualTo("user");
        JsonNode data = JsonMapper.builder().build().readTree(body.get("messages").get(1).get("content").asString());
        assertThat(data.propertyNames()).containsExactly("items", "transcript");
        assertThat(data.get("transcript").asString()).isEqualTo("five tomatoes, three herrings, a box of something");
        assertThat(data.get("items").toString())
                .isEqualTo("[{\"id\":11,\"name\":\"Tomatoes\",\"unit\":\"kg\"},{\"id\":12,\"name\":\"Eggs\",\"unit\":\"pcs\"}]");
    }

    @Test
    void anAnswerThatIsNotJsonIsNothingUnderstood() {
        answer = completion("Here you go: five tomatoes");
        assertThat(extractor.extract("five tomatoes", ITEMS).isEmpty()).isTrue();

        answer = "{\"choices\": []}";
        assertThat(extractor.extract("five tomatoes", ITEMS).isEmpty()).isTrue();

        answer = "not json at all";
        assertThat(extractor.extract("five tomatoes", ITEMS).isEmpty()).isTrue();
    }

    @Test
    void aStatusOrASlowAnswerIsAFailureWithoutTheKey() {
        for (int code : new int[] {401, 422, 429, 500}) {
            status = code;
            answer = "{\"message\": \"nope\"}";
            assertThatThrownBy(() -> extractor.extract("five tomatoes", ITEMS))
                    .isInstanceOfSatisfying(CountExtractionException.class, e -> {
                        assertThat(e.reason()).isEqualTo(CountExtractionException.Reason.PROVIDER_ERROR);
                        assertThat(e.getMessage()).contains(String.valueOf(code)).doesNotContain(KEY);
                    });
        }
        status = 200;
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

    private static String completion(String content) {
        String quoted = JsonMapper.builder().build().writeValueAsString(content);
        return "{\"id\": \"x\", \"object\": \"chat.completion\", \"model\": \"mistral-small-2603\", \"choices\": "
                + "[{\"index\": 0, \"message\": {\"role\": \"assistant\", \"content\": " + quoted + "}, "
                + "\"finish_reason\": \"stop\"}], \"usage\": {\"prompt_tokens\": 1, \"completion_tokens\": 1, "
                + "\"total_tokens\": 2}}";
    }
}
