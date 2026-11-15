package com.nereonix.nereonet_mvp.core.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nereonix.nereonet_mvp.core.ai.InvoiceReader.Page;
import com.nereonix.nereonet_mvp.core.ai.InvoiceReadingException.Reason;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * P62: the real invoice reader against a local HTTP server: POST /v1/ocr with the Bearer key, the pinned OCR model, one
 * page as a data URL (image_url, or document_url for a PDF), tables as markdown, blocks and block confidence; the
 * table given apart with its placeholder; a line's confidence from its table block; a sideways page turned and read
 * once more; a page without a table structured by the chat model from its OCR text only; a status, a slow answer or
 * no key is a failure, never with the key in it.
 */
class MistralInvoiceReaderTest {

    private static final String KEY = "mistral-test-key-0123456789";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String TABLE = "| No | Description | Unit | Qty | Unit price | Amount |\\n|---|---|---|---|---|---|\\n"
            + "| 1 | Tomatoes | KG | 25,000 | 1,85 | 46,25 |\\n| 2 | Eggs | PCS | 360 | 0,21 | 75,60 |";
    private static final String TABLE_ANSWER = """
            {"pages": [{"index": 0,
              "markdown": "# Kestrel Ship Supply S.A.\\nInvoice No: A-4471\\nDate: 12/11/2026\\nYour order: SR-000012\\nPage 1 of 1\\n\\n[tbl-0.md](tbl-0.md)\\n\\nPage total: 121,85",
              "tables": [{"id": "tbl-0.md", "content": "%s", "format": "markdown"}],
              "blocks": [{"type": "title", "top_left_x": 10, "top_left_y": 10, "bottom_right_x": 600, "bottom_right_y": 50,
                          "content": "Kestrel Ship Supply S.A.", "confidence_scores": {"average_content_confidence_score": 0.99}},
                         {"type": "table", "table_id": "tbl-0.md", "top_left_x": 10, "top_left_y": 200,
                          "bottom_right_x": 900, "bottom_right_y": 600,
                          "confidence_scores": {"average_content_confidence_score": 0.62,
                                                "minimum_content_confidence_score": 0.31}}],
              "confidence_scores": {"average_page_confidence_score": 0.9, "minimum_page_confidence_score": 0.3},
              "dimensions": {"dpi": 200, "height": 2200, "width": 1700}}],
             "model": "mistral-ocr-4-1", "usage_info": {"pages_processed": 1}}
            """.formatted(TABLE);
    private static final String SIDEWAYS_ANSWER = """
            {"pages": [{"index": 0, "markdown": "a\\nb\\nc\\nd", "tables": [],
              "blocks": [%s]}], "model": "mistral-ocr-4-1"}
            """.formatted(String.join(",", List.of(tall(), tall(), tall(), tall())));
    private static final String TEXT_ONLY_ANSWER = """
            {"pages": [{"index": 0, "markdown": "Kestrel Ship Supply\\n1 Tomatoes KG 25,000 1,85 46,25\\nPage total 46,25",
              "tables": [], "blocks": [{"type": "text", "top_left_x": 0, "top_left_y": 0, "bottom_right_x": 900,
              "bottom_right_y": 40}], "confidence_scores": {"average_page_confidence_score": 0.88}}]}
            """;

    private static String tall() {
        return "{\"type\": \"text\", \"top_left_x\": 0, \"top_left_y\": 0, \"bottom_right_x\": 40, \"bottom_right_y\": 400}";
    }

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final Deque<String> answers = new ArrayDeque<>();
    private volatile int status = 200;
    private volatile long delayMillis;
    private MistralSettings settings;
    private MistralInvoiceReader reader;

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
            String answer;
            synchronized (answers) {
                answer = answers.size() > 1 ? answers.poll() : answers.peek();
            }
            byte[] body = (answer == null ? "{}" : answer).getBytes(StandardCharsets.UTF_8);
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
                "mistral-small-2603", Duration.ofSeconds(2), false, "mistral-ocr-4-1", Duration.ofSeconds(2));
        reader = new MistralInvoiceReader(settings);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void answer(String... bodies) {
        synchronized (answers) {
            answers.clear();
            answers.addAll(List.of(bodies));
        }
    }

    private static JsonNode body(String request) {
        return MAPPER.readTree(request.substring(request.lastIndexOf(" | ") + 3));
    }

    private static byte[] jpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }

    @Test
    void onePageGoesToTheOcrAndItsTableIsRead() throws IOException {
        answer(TABLE_ANSWER);
        byte[] photo = jpeg(30, 40);
        PageReading reading = reader.readPage(new Page(1, photo, "image/jpeg"));

        assertThat(requests).hasSize(1);
        String request = requests.getFirst();
        assertThat(request).startsWith("POST /v1/ocr | Bearer " + KEY + " | ");
        JsonNode body = body(request);
        assertThat(body.path("model").asString()).isEqualTo("mistral-ocr-4-1");
        assertThat(body.path("document").path("type").asString()).isEqualTo("image_url");
        assertThat(body.path("document").path("image_url").asString())
                .isEqualTo("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(photo));
        assertThat(body.path("table_format").asString()).isEqualTo("markdown");
        assertThat(body.path("include_blocks").asBoolean()).isTrue();
        assertThat(body.path("confidence_scores_granularity").asString()).isEqualTo("block");
        assertThat(body.path("include_image_base64").asBoolean()).isFalse();
        // Only the page: nothing else in the request.
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("model", "document", "table_format",
                "include_blocks", "confidence_scores_granularity", "include_image_base64");

        assertThat(reading.tableFound()).isTrue();
        assertThat(reading.supplier()).isEqualTo("Kestrel Ship Supply S.A.");
        assertThat(reading.invoiceNumber()).isEqualTo("A-4471");
        assertThat(reading.orderReference()).isEqualTo("SR-000012");
        assertThat(reading.pageTotal()).isEqualByComparingTo("121.85");
        assertThat(reading.lines()).hasSize(2);
        assertThat(reading.lines().getFirst().quantity()).isEqualByComparingTo("25");
        assertThat(reading.lines().getFirst().amount()).isEqualByComparingTo("46.25");
        // The table block's average confidence.
        assertThat(reading.lines()).allMatch(line -> line.confidence() == 0.62);
    }

    @Test
    void aPdfGoesAsADocument() {
        answer(TABLE_ANSWER);
        byte[] pdf = "%PDF-1.4 sample".getBytes(StandardCharsets.ISO_8859_1);
        reader.readPage(new Page(1, pdf, "application/pdf"));
        JsonNode document = body(requests.getFirst()).path("document");
        assertThat(document.path("type").asString()).isEqualTo("document_url");
        assertThat(document.path("document_url").asString())
                .isEqualTo("data:application/pdf;base64," + Base64.getEncoder().encodeToString(pdf));
    }

    @Test
    void aSidewaysPhotoIsTurnedAndReadOnceMore() throws IOException {
        answer(SIDEWAYS_ANSWER, TABLE_ANSWER);
        PageReading reading = reader.readPage(new Page(1, jpeg(30, 40), "image/jpeg"));
        assertThat(requests).hasSize(2);
        String turned = body(requests.get(1)).path("document").path("image_url").asString();
        assertThat(turned).startsWith("data:image/jpeg;base64,");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(
                Base64.getDecoder().decode(turned.substring("data:image/jpeg;base64,".length()))));
        assertThat(image.getWidth()).isEqualTo(40);
        assertThat(image.getHeight()).isEqualTo(30);
        assertThat(reading.lines()).hasSize(2);

        // Still nothing after turning it (and no text for the chat model either): a page without a table, once.
        requests.clear();
        answer(SIDEWAYS_ANSWER.replace("a\\nb\\nc\\nd", ""));
        PageReading none = reader.readPage(new Page(2, jpeg(30, 40), "image/jpeg"));
        assertThat(none.tableFound()).isFalse();
        assertThat(requests).hasSize(2);
    }

    @Test
    void aPageWithoutATableIsStructuredFromItsOcrTextOnly() throws IOException {
        String structured = "{\"lines\": [{\"no\": \"1\", \"description\": \"Tomatoes\", \"unit\": \"KG\", "
                + "\"quantity\": \"25,000\", \"unit_price\": \"1,85\", \"amount\": \"46,25\"}], \"page_total\": \"46,25\"}";
        answer(TEXT_ONLY_ANSWER, "{\"choices\": [{\"message\": {\"content\": " + MAPPER.writeValueAsString(structured) + "}}]}");
        PageReading reading = reader.readPage(new Page(1, jpeg(30, 40), "image/jpeg"));

        assertThat(requests).hasSize(2);
        assertThat(requests.get(1)).startsWith("POST /v1/chat/completions | Bearer " + KEY + " | ");
        JsonNode chat = body(requests.get(1));
        assertThat(chat.path("model").asString()).isEqualTo("mistral-small-2603");
        assertThat(chat.path("response_format").path("type").asString()).isEqualTo("json_object");
        JsonNode user = MAPPER.readTree(chat.path("messages").path(1).path("content").asString());
        assertThat(user.propertyNames()).containsExactly("ocr_text");
        assertThat(user.path("ocr_text").asString()).contains("1 Tomatoes KG 25,000 1,85 46,25");

        assertThat(reading.tableFound()).isTrue();
        assertThat(reading.lines()).hasSize(1);
        assertThat(reading.lines().getFirst().quantity()).isEqualByComparingTo("25");
        assertThat(reading.lines().getFirst().unitPrice()).isEqualByComparingTo("1.85");
        assertThat(reading.lines().getFirst().confidence()).isEqualTo(0.88);
        assertThat(reading.pageTotal()).isEqualByComparingTo("46.25");
    }

    @Test
    void failures() throws IOException {
        byte[] photo = jpeg(30, 40);
        answer("{\"message\": \"Unauthorized\"}");
        status = 401;
        assertThatThrownBy(() -> reader.readPage(new Page(1, photo, "image/jpeg")))
                .isInstanceOfSatisfying(InvoiceReadingException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.PROVIDER_ERROR);
                    assertThat(e.getMessage()).contains("401").doesNotContain(KEY);
                });

        status = 200;
        delayMillis = 3000;
        assertThatThrownBy(() -> reader.readPage(new Page(1, photo, "image/jpeg")))
                .isInstanceOfSatisfying(InvoiceReadingException.class, e -> assertThat(e.reason()).isEqualTo(Reason.TIMEOUT));
        delayMillis = 0;

        answer("not json");
        assertThatThrownBy(() -> reader.readPage(new Page(1, photo, "image/jpeg")))
                .isInstanceOfSatisfying(InvoiceReadingException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.PROVIDER_ERROR));

        requests.clear();
        MistralInvoiceReader noKey = new MistralInvoiceReader(new MistralSettings(settings.baseUrl(), "",
                "mistral-small-2603", Duration.ofSeconds(2), false));
        assertThatThrownBy(() -> noKey.readPage(new Page(1, photo, "image/jpeg")))
                .isInstanceOfSatisfying(InvoiceReadingException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.NOT_CONFIGURED));
        assertThat(requests).isEmpty();
        assertThat(settings.toString()).doesNotContain(KEY).contains("ocrModel=mistral-ocr-4-1");
    }

    @Test
    void theDefaultOcrModelIsPinned() {
        assertThat(MistralSettings.DEFAULT_OCR_MODEL).isEqualTo("mistral-ocr-4-1").doesNotContain("latest");
        MistralSettings defaults = new MistralSettings("", "", "", null, false, "", null);
        assertThat(defaults.ocrModel()).isEqualTo("mistral-ocr-4-1");
        assertThat(defaults.ocrTimeout()).isEqualTo(Duration.ofSeconds(60));
    }
}
