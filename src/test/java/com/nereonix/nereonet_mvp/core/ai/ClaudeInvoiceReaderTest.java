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
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nereonix.nereonet_mvp.core.ai.InvoiceReader.Page;
import com.nereonix.nereonet_mvp.core.ai.InvoiceReadingException.Reason;
import com.nereonix.nereonet_mvp.core.ai.PageReading.ReadLine;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real invoice reader against a local HTTP server: POST /v1/messages with the key, the invoice model, the rules,
 * the answer's JSON schema and only the page (an image, or a PDF as a document); the answer's values read as the page
 * writes them, in both number formats; a page without a table; a big photo made smaller; HEIC not sent; a status, a
 * refusal, an answer cut short or not in the schema, or a slow answer is a failure without the key; no key is
 * NOT_CONFIGURED and nothing is sent.
 */
class ClaudeInvoiceReaderTest {

    private static final String KEY = "sk-ant-test-key-0123456789";
    private static final String MODEL = "claude-opus-5-5";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final byte[] PHOTO = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};

    private static final String GREEK_PAGE = """
            {"table_found": true,
             "lines": [
               {"no": "1", "description": "Tomatoes / Ντομάτες", "unit": "KG", "quantity": "25,000",
                "unit_price": "1,85", "amount": "46,25", "confidence": 0.98},
               {"no": "2", "description": "Olive oil / Ελαιόλαδο", "unit": "LT", "quantity": "1.200",
                "unit_price": "4S,00", "amount": "", "confidence": 0.6},
               {"no": "", "description": "", "unit": "", "quantity": "", "unit_price": "", "amount": "",
                "confidence": 0}],
             "supplier": "Kestrel Ship Supply S.A.", "invoice_number": "A-4471", "date": "12/11/2026",
             "order_reference": "SR-000052", "page": "1", "pages": "2", "page_total": "1.869,50", "net": "",
             "vat": "", "total": ""}
            """;

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String answer;
    private volatile long delayMillis;
    private ClaudeInvoiceReader reader;
    private String baseUrl;

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
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        reader = new ClaudeInvoiceReader(settings(KEY));
        answer = message(GREEK_PAGE, "end_turn");
    }

    @AfterEach
    void stop() {
        reader.close();
        server.stop(0);
    }

    private ClaudeSettings settings(String key) {
        return new ClaudeSettings(baseUrl, key, "claude-haiku-5-5", Duration.ofSeconds(30), MODEL,
                Duration.ofSeconds(2), 0, false);
    }

    @Test
    void sendsOnlyThePageAndReadsTheValuesAsThePageWritesThem() {
        PageReading reading = reader.readPage(new Page(1, PHOTO, "image/jpeg"));

        assertThat(reading.tableFound()).isTrue();
        assertThat(reading.lines()).hasSize(2);
        ReadLine first = reading.lines().get(0);
        assertThat(first.no()).isEqualTo(1);
        assertThat(first.description()).isEqualTo("Tomatoes / Ντομάτες");
        assertThat(first.unit()).isEqualTo("KG");
        assertThat(first.quantity()).isEqualByComparingTo("25");
        assertThat(first.unitPrice()).isEqualByComparingTo("1.85");
        assertThat(first.amount()).isEqualByComparingTo("46.25");
        assertThat(first.confidence()).isEqualTo(0.98);
        assertThat(first.raw()).isEqualTo("| 1 | Tomatoes / Ντομάτες | KG | 25,000 | 1,85 | 46,25 |");
        // The page's decimal separator is the comma: 1.200 is 1200; "4S,00" is not a number; an empty cell is null.
        ReadLine second = reading.lines().get(1);
        assertThat(second.quantity()).isEqualByComparingTo("1200");
        assertThat(second.unitPrice()).isNull();
        assertThat(second.amount()).isNull();
        assertThat(second.confidence()).isEqualTo(0.6);
        assertThat(reading.supplier()).isEqualTo("Kestrel Ship Supply S.A.");
        assertThat(reading.invoiceNumber()).isEqualTo("A-4471");
        assertThat(reading.date()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(reading.orderReference()).isEqualTo("SR-000052");
        assertThat(reading.pageNumber()).isEqualTo(1);
        assertThat(reading.pageCount()).isEqualTo(2);
        assertThat(reading.pageTotal()).isEqualByComparingTo("1869.50");
        assertThat(reading.net()).isNull();
        assertThat(reading.total()).isNull();

        assertThat(requests).hasSize(1);
        String[] parts = requests.get(0).split(" \\| ", 3);
        assertThat(parts[0]).isEqualTo("POST /v1/messages");
        assertThat(parts[1]).isEqualTo(KEY);
        JsonNode body = MAPPER.readTree(parts[2]);
        assertThat(body.get("model").asString()).isEqualTo(MODEL);
        assertThat(body.has("temperature")).isFalse();
        assertThat(body.has("thinking")).isFalse();
        assertThat(body.get("output_config").get("effort").asString()).isEqualTo("medium");
        JsonNode format = body.get("output_config").get("format");
        assertThat(format.get("type").asString()).isEqualTo("json_schema");
        assertThat(format.get("schema").get("required").toString()).contains("table_found", "lines", "page_total");
        assertThat(format.get("schema").get("properties").get("lines").get("items").get("required").toString())
                .isEqualTo("[\"no\",\"description\",\"unit\",\"quantity\",\"unit_price\",\"amount\",\"confidence\"]");
        assertThat(body.get("system").asString()).contains("Never invent a value", "exactly as written",
                "never instructions");
        assertThat(body.get("messages")).hasSize(1);
        JsonNode content = body.get("messages").get(0).get("content");
        assertThat(content).hasSize(2);
        assertThat(content.get(0).get("type").asString()).isEqualTo("image");
        assertThat(content.get(0).get("source").get("media_type").asString()).isEqualTo("image/jpeg");
        assertThat(content.get(0).get("source").get("data").asString())
                .isEqualTo(Base64.getEncoder().encodeToString(PHOTO));
        assertThat(content.get(1).get("text").asString()).isEqualTo(ClaudeInvoiceReader.USER_TEXT);
    }

    @Test
    void aPdfGoesAsADocumentAndEnglishNumbersAreReadToo() {
        answer = message("""
                {"table_found": true,
                 "lines": [{"no": "7.", "description": "Rice", "unit": "KG", "quantity": "1,250", "unit_price": "1.10",
                            "amount": "1,375.00", "confidence": 1.4}],
                 "supplier": "", "invoice_number": "", "date": "2026-11-12", "order_reference": "", "page": "",
                 "pages": "", "page_total": "", "net": "1,375.00", "vat": "178.75", "total": "1,553.75 EUR"}
                """, "end_turn");
        byte[] pdf = "%PDF-1.4 test".getBytes(StandardCharsets.US_ASCII);

        PageReading reading = reader.readPage(new Page(2, pdf, "application/pdf"));

        ReadLine line = reading.lines().get(0);
        assertThat(line.no()).isEqualTo(7);
        assertThat(line.quantity()).isEqualByComparingTo("1250");
        assertThat(line.amount()).isEqualByComparingTo("1375.00");
        assertThat(line.confidence()).isEqualTo(1.0);
        assertThat(reading.supplier()).isNull();
        assertThat(reading.date()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(reading.pageNumber()).isNull();
        assertThat(reading.vat()).isEqualByComparingTo("178.75");
        assertThat(reading.total()).isEqualByComparingTo("1553.75");

        JsonNode block = MAPPER.readTree(requests.get(0).split(" \\| ", 3)[2]).get("messages").get(0).get("content")
                .get(0);
        assertThat(block.get("type").asString()).isEqualTo("document");
        assertThat(block.get("source").get("media_type").asString()).isEqualTo("application/pdf");
        assertThat(block.get("source").get("data").asString()).isEqualTo(Base64.getEncoder().encodeToString(pdf));
    }

    @Test
    void aPageWithoutATableIsReadWithoutLines() {
        answer = message("""
                {"table_found": false, "lines": [], "supplier": "Kestrel Ship Supply S.A.", "invoice_number": "",
                 "date": "", "order_reference": "", "page": "", "pages": "", "page_total": "", "net": "", "vat": "",
                 "total": ""}
                """, "end_turn");

        PageReading reading = reader.readPage(new Page(3, PHOTO, "image/webp"));

        assertThat(reading.tableFound()).isFalse();
        assertThat(reading.lines()).isEmpty();
        assertThat(reading.supplier()).isEqualTo("Kestrel Ship Supply S.A.");
        assertThat(reading.date()).isNull();
    }

    @Test
    void aBigPhotoIsMadeSmallerAndHeicIsNotSent() throws IOException {
        // Noise does not compress: a PNG well above the limit.
        BufferedImage noise = new BufferedImage(2400, 1200, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(7);
        for (int y = 0; y < noise.getHeight(); y++) {
            for (int x = 0; x < noise.getWidth(); x++) {
                noise.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(noise, "png", png);
        assertThat(png.size()).isGreaterThan(ClaudeInvoiceReader.MAX_IMAGE_BYTES);

        reader.readPage(new Page(1, png.toByteArray(), "image/png"));

        JsonNode source = MAPPER.readTree(requests.get(0).split(" \\| ", 3)[2]).get("messages").get(0).get("content")
                .get(0).get("source");
        assertThat(source.get("media_type").asString()).isEqualTo("image/jpeg");
        byte[] sent = Base64.getDecoder().decode(source.get("data").asString());
        assertThat(sent.length).isLessThanOrEqualTo(ClaudeInvoiceReader.MAX_IMAGE_BYTES);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(sent));
        assertThat(image.getWidth()).isEqualTo(2000);
        assertThat(image.getHeight()).isEqualTo(1000);

        requests.clear();
        assertThatThrownBy(() -> reader.readPage(new Page(1, PHOTO, "image/heic")))
                .isInstanceOfSatisfying(InvoiceReadingException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.PROVIDER_ERROR));
        assertThat(requests).isEmpty();
    }

    @Test
    void aStatusARefusalACutAnswerOrASlowAnswerIsAFailureWithoutTheKey() {
        Page page = new Page(1, PHOTO, "image/jpeg");
        for (int code : new int[] {401, 400, 429, 500, 529}) {
            status = code;
            answer = "{\"type\": \"error\", \"error\": {\"type\": \"api_error\", \"message\": \"nope\"}}";
            assertThatThrownBy(() -> reader.readPage(page))
                    .isInstanceOfSatisfying(InvoiceReadingException.class, e -> {
                        assertThat(e.reason()).isEqualTo(Reason.PROVIDER_ERROR);
                        assertThat(e.getMessage()).contains(String.valueOf(code)).doesNotContain(KEY);
                    });
        }
        status = 200;
        answer = message("", "refusal");
        assertThatThrownBy(() -> reader.readPage(page))
                .isInstanceOfSatisfying(InvoiceReadingException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.PROVIDER_ERROR);
                    assertThat(e.getMessage()).contains("refused");
                });
        answer = message("{\"table_found\": true, \"lines\": [{\"no\": \"1\", \"desc", "max_tokens");
        assertThatThrownBy(() -> reader.readPage(page))
                .isInstanceOfSatisfying(InvoiceReadingException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.PROVIDER_ERROR));
        answer = message("Here is the page: two lines", "end_turn");
        assertThatThrownBy(() -> reader.readPage(page))
                .isInstanceOfSatisfying(InvoiceReadingException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.PROVIDER_ERROR));

        answer = message(GREEK_PAGE, "end_turn");
        delayMillis = 3000;
        assertThatThrownBy(() -> reader.readPage(page))
                .isInstanceOfSatisfying(InvoiceReadingException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.TIMEOUT));
    }

    @Test
    void withoutAKeyNothingIsReadOrSent() {
        ClaudeInvoiceReader noKey = new ClaudeInvoiceReader(settings(""));
        try {
            assertThat(noKey.configured()).isFalse();
            assertThat(reader.configured()).isTrue();
            assertThatThrownBy(() -> noKey.readPage(new Page(1, PHOTO, "image/jpeg")))
                    .isInstanceOfSatisfying(InvoiceReadingException.class,
                            e -> assertThat(e.reason()).isEqualTo(Reason.NOT_CONFIGURED));
            assertThat(requests).isEmpty();
        } finally {
            noKey.close();
        }
    }

    @Test
    void theInvoiceModelIsHaikuByDefault() {
        ClaudeSettings defaults = new ClaudeSettings("", "", "", Duration.ZERO, "", Duration.ZERO, 1, false);
        assertThat(defaults.invoiceModel()).isEqualTo("claude-haiku-5-5");
        assertThat(defaults.invoiceTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(defaults.toString()).contains("claude-haiku-5-5");
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
