package com.nereonix.nereonet_mvp.core.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nereonix.nereonet_mvp.core.ai.InvoiceReader.Page;
import com.nereonix.nereonet_mvp.core.ai.InvoiceReadingException.Reason;
import com.nereonix.nereonet_mvp.core.ai.PageReading.ReadLine;

import tools.jackson.databind.JsonNode;

/**
 * P67: the local OCR reads the synthetic sample invoice A-4471 (samples/invoice/): both pages as scanned and as
 * photographed (tilted), page 1 turned sideways, and page 1 as a PDF. Every line's number, quantity, price and amount
 * and the page's totals are as its expected JSON says. Needs the models (OCR_MODEL_DIR, else models/ocr, see
 * docs/configuration.md): without them those tests are skipped. Without models nothing is read (NOT_CONFIGURED);
 * a WebP page cannot be decoded (PROVIDER_ERROR).
 */
class LocalInvoiceReaderTest {

    private static final Path SAMPLES = Path.of("samples/invoice");
    private static final JsonNode EXPECTED = new FakeInvoiceReader("samples/invoice").sample();

    private static LocalInvoiceReader reader;

    @BeforeAll
    static void models() {
        String dir = System.getenv("OCR_MODEL_DIR");
        OcrSettings settings = new OcrSettings(dir == null || dir.isBlank() ? "models/ocr" : dir, 1600, 4);
        reader = settings.configured() ? new LocalInvoiceReader(settings) : null;
    }

    @AfterAll
    static void close() {
        if (reader != null) {
            reader.close();
        }
    }

    private static void needsModels() {
        Assumptions.assumeTrue(reader != null, "OCR models not found (OCR_MODEL_DIR or models/ocr)");
    }

    @Test
    void scannedPages() throws Exception {
        needsModels();
        assertPage(1, reader.readPage(page(1, "invoice-A-4471-p1.png", "image/png")), true);
        assertPage(2, reader.readPage(page(2, "invoice-A-4471-p2.png", "image/png")), true);
    }

    @Test
    void photographedPages() throws Exception {
        needsModels();
        assertPage(1, reader.readPage(page(1, "invoice-A-4471-p1-photo.jpg", "image/jpeg")), true);
        // The photo of page 2 blurs its invoice number; the lines and totals are read.
        assertPage(2, reader.readPage(page(2, "invoice-A-4471-p2-photo.jpg", "image/jpeg")), false);
    }

    @Test
    void aPageTurnedSideways() throws Exception {
        needsModels();
        BufferedImage image = ImageIO.read(SAMPLES.resolve("invoice-A-4471-p1.png").toFile());
        byte[] turned = png(LocalInvoiceReader.rotate(image, false));

        assertPage(1, reader.readPage(new Page(1, turned, "image/png")), true);
    }

    @Test
    void aPdf() throws Exception {
        needsModels();
        BufferedImage image = ImageIO.read(SAMPLES.resolve("invoice-A-4471-p1.png").toFile());
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage pdfPage = new PDPage(PDRectangle.A4);
            document.addPage(pdfPage);
            PDImageXObject picture = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream content = new PDPageContentStream(document, pdfPage)) {
                content.drawImage(picture, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
            }
            document.save(pdf);
        }

        assertPage(1, reader.readPage(new Page(1, pdf.toByteArray(), "application/pdf")), true);
    }

    @Test
    void withoutModelsNothingIsRead() {
        LocalInvoiceReader none = new LocalInvoiceReader(new OcrSettings("does-not-exist", 1600, 2));

        assertThat(none.configured()).isFalse();
        assertThatThrownBy(() -> none.readPage(new Page(1, new byte[]{1}, "image/png")))
                .isInstanceOfSatisfying(InvoiceReadingException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.NOT_CONFIGURED));
    }

    @Test
    void aWebpPageCannotBeDecoded() {
        needsModels();
        byte[] webp = "RIFF\0\0\0\0WEBPVP8 ".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

        assertThatThrownBy(() -> reader.readPage(new Page(1, webp, "image/webp")))
                .isInstanceOfSatisfying(InvoiceReadingException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.PROVIDER_ERROR));
    }

    private static Page page(int number, String file, String contentType) throws Exception {
        return new Page(number, Files.readAllBytes(SAMPLES.resolve(file)), contentType);
    }

    private static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static void assertPage(int page, PageReading reading, boolean invoiceNumber) {
        assertThat(reading.tableFound()).isTrue();
        if (invoiceNumber) {
            assertThat(reading.invoiceNumber()).isEqualTo("A-4471");
        }
        assertThat(reading.orderReference()).isEqualTo("SR-000052");
        assertThat(reading.pageNumber()).isEqualTo(page);
        assertThat(reading.pageCount()).isEqualTo(2);
        assertThat(reading.pageTotal())
                .isEqualByComparingTo(EXPECTED.path("pages").path(page - 1).path("pageTotal").asString());
        if (page == 2) {
            assertThat(reading.net()).isEqualByComparingTo(EXPECTED.path("net").asString());
            assertThat(reading.vat()).isEqualByComparingTo(EXPECTED.path("vat").asString());
            assertThat(reading.total()).isEqualByComparingTo(EXPECTED.path("total").asString());
        }
        List<JsonNode> expected = new ArrayList<>();
        EXPECTED.path("lines").forEach(line -> {
            if (line.path("page").asInt() == page) {
                expected.add(line);
            }
        });
        assertThat(reading.lines()).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            JsonNode line = expected.get(i);
            ReadLine read = reading.lines().get(i);
            assertThat(read.no()).isEqualTo(line.path("no").asInt());
            assertThat(read.unit()).isEqualToIgnoringCase(line.path("unit").asString());
            assertThat(read.quantity()).as("quantity of line %d", read.no())
                    .isEqualByComparingTo(line.path("quantity").asString());
            assertThat(read.unitPrice()).as("price of line %d", read.no())
                    .isEqualByComparingTo(line.path("unitPrice").asString());
            assertThat(read.amount()).as("amount of line %d", read.no())
                    .isEqualByComparingTo(line.path("amount").asString());
            assertThat(read.confidence()).isBetween(0.0, 1.0);
        }
    }
}
