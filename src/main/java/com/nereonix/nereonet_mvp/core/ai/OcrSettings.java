package com.nereonix.nereonet_mvp.core.ai;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The settings of the local OCR (LocalInvoiceReader), app.ocr.*:
 *   model-dir  OCR_MODEL_DIR, default models/ocr (from the working directory): det.onnx (PP-OCRv5 text detection),
 *              rec.onnx (text recognition, el_PP-OCRv5 for Greek and English) and the recognition model's characters,
 *              rec.yml (its inference.yml) or dict.txt. The models are not in the repository (docs/configuration.md
 *              says where to get them).
 *   max-side   the page is scaled down to this many pixels on its long side for the detection, default 1600
 *   threads    CPU threads one reading uses, default 2
 */
@Component
public class OcrSettings {

    private final Path modelDir;
    private final int maxSide;
    private final int threads;

    public OcrSettings(@Value("${app.ocr.model-dir:models/ocr}") String modelDir,
                       @Value("${app.ocr.max-side:1600}") int maxSide,
                       @Value("${app.ocr.threads:2}") int threads) {
        this.modelDir = Path.of(modelDir == null || modelDir.isBlank() ? "models/ocr" : modelDir.strip());
        this.maxSide = Math.max(640, maxSide);
        this.threads = Math.max(1, threads);
    }

    public Path detectionModel() {
        return modelDir.resolve("det.onnx");
    }

    public Path recognitionModel() {
        return modelDir.resolve("rec.onnx");
    }

    /** rec.yml when it is there, else dict.txt. */
    public Path dictionary() {
        Path yml = modelDir.resolve("rec.yml");
        return Files.isRegularFile(yml) ? yml : modelDir.resolve("dict.txt");
    }

    /** The two models and the characters are in model-dir. */
    public boolean configured() {
        return Files.isRegularFile(detectionModel()) && Files.isRegularFile(recognitionModel())
                && Files.isRegularFile(dictionary());
    }

    public Path modelDir() {
        return modelDir;
    }

    public int maxSide() {
        return maxSide;
    }

    public int threads() {
        return threads;
    }

    @Override
    public String toString() {
        return "OcrSettings[modelDir=" + modelDir + ", maxSide=" + maxSide + ", threads=" + threads
                + (configured() ? "" : ", models missing") + "]";
    }
}
