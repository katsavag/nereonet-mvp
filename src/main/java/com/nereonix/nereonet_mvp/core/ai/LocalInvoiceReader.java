package com.nereonix.nereonet_mvp.core.ai;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import com.nereonix.nereonet_mvp.core.ai.InvoiceReadingException.Reason;
import com.nereonix.nereonet_mvp.core.ai.OnnxOcr.TextBox;

import jakarta.annotation.PreDestroy;

/**
 * The invoice reader that runs on the server: PaddleOCR models in ONNX Runtime (OnnxOcr), the boxes of text turned
 * into the page's text and table (OcrLayout) and read by InvoicePageParser, as Mistral's answer is. Nothing leaves the
 * server and there is no key; the models are files in app.ocr.model-dir (OcrSettings), loaded on the first page.
 *   - JPEG and PNG are read as they are; a PDF page by page, at 200 dpi, its pages' lines one after the other. WebP
 *     and HEIC cannot be decoded here: such a page fails (PROVIDER_ERROR) and the cook can photograph it again.
 *   - Sideways: no table found but most boxes taller than wide: the page is turned 90 degrees one way, then the other,
 *     and the first turn that finds a table is kept.
 *   - One page at a time on the whole server (the models take some 300 MB and the CPU while they read).
 * Logs say how a page ended (lines, time), never its text. Active when app.invoice.reader is local (the default) and
 * app.mistral.fake is not true.
 */
@Component
@ConditionalOnExpression("!${app.mistral.fake:false} and '${app.invoice.reader:local}' == 'local'")
public class LocalInvoiceReader implements InvoiceReader {

    private static final Logger log = LoggerFactory.getLogger(LocalInvoiceReader.class);

    static final float PDF_DPI = 200f;
    static final int MAX_PDF_PAGES = 10;

    private final OcrSettings settings;
    private OnnxOcr ocr;

    public LocalInvoiceReader(OcrSettings settings) {
        this.settings = settings;
        if (!settings.configured()) {
            log.info("Local OCR: models not found in {}, invoices are not read", settings.modelDir());
        }
    }

    @Override
    public boolean configured() {
        return settings.configured();
    }

    @Override
    public synchronized PageReading readPage(Page page) {
        if (!settings.configured()) {
            throw new InvoiceReadingException(Reason.NOT_CONFIGURED, "Local OCR: models not found");
        }
        long start = System.currentTimeMillis();
        List<BufferedImage> images = images(page);
        List<PageReading> readings = new ArrayList<>();
        for (BufferedImage image : images) {
            readings.add(read(image));
        }
        PageReading reading = MistralInvoiceReader.merge(readings);
        log.info("Local OCR: page {} read in {} ms, {} lines{}", page.number(), System.currentTimeMillis() - start,
                reading.lines().size(), reading.tableFound() ? "" : ", no table");
        return reading;
    }

    private PageReading read(BufferedImage image) {
        List<TextBox> boxes = boxes(image);
        PageReading reading = OcrLayout.read(boxes);
        if (reading.tableFound() || !sideways(boxes)) {
            return reading;
        }
        for (boolean clockwise : new boolean[]{true, false}) {
            PageReading turned = OcrLayout.read(boxes(rotate(image, clockwise)));
            if (turned.tableFound()) {
                log.info("Local OCR: page read after turning it 90 degrees");
                return turned;
            }
        }
        return reading;
    }

    private List<TextBox> boxes(BufferedImage image) {
        try {
            return engine().read(image);
        } catch (Exception e) {
            log.warn("Local OCR: {} ({})", Reason.PROVIDER_ERROR, e.getClass().getSimpleName());
            throw new InvoiceReadingException(Reason.PROVIDER_ERROR, "Local OCR: " + e.getClass().getSimpleName());
        }
    }

    private OnnxOcr engine() throws Exception {
        if (ocr == null) {
            ocr = new OnnxOcr(settings.detectionModel(), settings.recognitionModel(), settings.dictionary(),
                    settings.maxSide(), settings.threads());
        }
        return ocr;
    }

    /** Most boxes (at least three) taller than wide: the text runs up or down the photo. */
    static boolean sideways(List<TextBox> boxes) {
        long tall = boxes.stream().filter(box -> box.height() > box.width()).count();
        return boxes.size() >= 3 && tall * 10 >= boxes.size() * 6L;
    }

    // The page's images: one for a photo, one per page (at most MAX_PDF_PAGES) for a PDF.
    private static List<BufferedImage> images(Page page) {
        try {
            if ("application/pdf".equals(page.contentType())) {
                try (PDDocument document = Loader.loadPDF(page.content())) {
                    PDFRenderer renderer = new PDFRenderer(document);
                    List<BufferedImage> images = new ArrayList<>();
                    for (int i = 0; i < Math.min(document.getNumberOfPages(), MAX_PDF_PAGES); i++) {
                        images.add(renderer.renderImageWithDPI(i, PDF_DPI, ImageType.RGB));
                    }
                    return images;
                }
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(page.content()));
            if (image != null) {
                return List.of(image);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Local OCR: page {} cannot be decoded ({})", page.number(), e.getClass().getSimpleName());
            throw new InvoiceReadingException(Reason.PROVIDER_ERROR, "Local OCR: cannot decode the page");
        }
        log.warn("Local OCR: page {} is {}, which cannot be decoded here", page.number(), page.contentType());
        throw new InvoiceReadingException(Reason.PROVIDER_ERROR, "Local OCR: " + page.contentType());
    }

    static BufferedImage rotate(BufferedImage image, boolean clockwise) {
        int w = image.getWidth();
        int h = image.getHeight();
        BufferedImage turned = new BufferedImage(h, w, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (clockwise) {
                    turned.setRGB(h - 1 - y, x, image.getRGB(x, y));
                } else {
                    turned.setRGB(y, w - 1 - x, image.getRGB(x, y));
                }
            }
        }
        return turned;
    }

    @PreDestroy
    synchronized void close() {
        if (ocr != null) {
            try {
                ocr.close();
            } catch (Exception e) {
                log.debug("Local OCR: closing the models failed", e);
            }
            ocr = null;
        }
    }
}
