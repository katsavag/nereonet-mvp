package com.nereonix.nereonet_mvp.core.ai;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;

/**
 * PaddleOCR (PP-OCRv5) in ONNX Runtime, on the server's CPU: the text detection model finds the boxes of text on the
 * page, the text recognition model reads each box. Plain Java around the two models, no OpenCV:
 *   - Detection (DB): the page scaled so its long side is at most maxSide (multiples of 32), normalised as the model's
 *     inference.yml says (BGR, ImageNet mean and std); the probability map above 0.3 split into connected regions;
 *     a region whose mean probability is below 0.6 is dropped; each kept region's box is widened as DB's unclip does
 *     (ratio 1.5). Boxes are upright rectangles: a slightly tilted photo still reads, a strongly tilted one does not.
 *   - Recognition (CTC): each box cut from the page, scaled to 48 pixels high, at least 320 wide (padded), normalised
 *     to [-1, 1]; the best character at each step, repeats and blanks dropped. A box's confidence is the mean
 *     probability of its characters.
 * The characters are the recognition model's character_dict (its inference.yml) or a dict.txt, one per line; a space
 * is added when the model has one more output than the list. The sessions are thread-safe; close frees them.
 */
final class OnnxOcr implements AutoCloseable {

    /** One box of text, in the page's pixels: left, top, right, bottom (exclusive). */
    record TextBox(int left, int top, int right, int bottom, String text, double confidence) {

        int width() {
            return right - left;
        }

        int height() {
            return bottom - top;
        }

        double centerX() {
            return (left + right) / 2.0;
        }

        double centerY() {
            return (top + bottom) / 2.0;
        }
    }

    static final float DET_THRESHOLD = 0.3f;
    static final double BOX_THRESHOLD = 0.6;
    static final double UNCLIP_RATIO = 1.5;
    static final int REC_HEIGHT = 48;
    static final int REC_MIN_WIDTH = 320;
    static final int REC_MAX_WIDTH = 3200;
    private static final int MIN_REGION_PIXELS = 10;

    private static final float[] DET_MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] DET_STD = {0.229f, 0.224f, 0.225f};

    private final OrtEnvironment env;
    private final OrtSession detector;
    private final OrtSession recognizer;
    private final List<String> characters;
    private final int maxSide;

    OnnxOcr(Path detModel, Path recModel, Path dictionary, int maxSide, int threads) throws OrtException, IOException {
        this.characters = characters(dictionary);
        // Errors only: loading the models otherwise logs a warning per unused weight.
        this.env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
            options.setIntraOpNumThreads(Math.max(1, threads));
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            this.detector = env.createSession(detModel.toString(), options);
            this.recognizer = env.createSession(recModel.toString(), options);
        }
        this.maxSide = Math.max(320, maxSide);
    }

    /** Every box of text on the page, top to bottom, then left to right. */
    List<TextBox> read(BufferedImage page) throws OrtException {
        BufferedImage image = rgb(page);
        List<TextBox> boxes = new ArrayList<>();
        for (int[] box : detect(image)) {
            TextBox read = recognize(image, box);
            if (read != null) {
                boxes.add(read);
            }
        }
        boxes.sort(Comparator.comparingInt(TextBox::top).thenComparingInt(TextBox::left));
        return boxes;
    }

    @Override
    public void close() throws OrtException {
        detector.close();
        recognizer.close();
    }

    // --- Detection -------------------------------------------------------------------------------------------------

    /** The boxes of text, as {left, top, right, bottom} in the page's pixels. */
    List<int[]> detect(BufferedImage image) throws OrtException {
        int w = image.getWidth();
        int h = image.getHeight();
        double scale = Math.min(1.0, (double) maxSide / Math.max(w, h));
        int rw = Math.max(32, (int) Math.round(w * scale / 32.0) * 32);
        int rh = Math.max(32, (int) Math.round(h * scale / 32.0) * 32);
        BufferedImage resized = resize(image, rw, rh);
        float[] data = new float[3 * rw * rh];
        int plane = rw * rh;
        int[] pixels = resized.getRGB(0, 0, rw, rh, null, 0, rw);
        for (int i = 0; i < plane; i++) {
            int p = pixels[i];
            float r = ((p >> 16) & 0xff) / 255f;
            float g = ((p >> 8) & 0xff) / 255f;
            float b = (p & 0xff) / 255f;
            // BGR, as the model was trained.
            data[i] = (b - DET_MEAN[0]) / DET_STD[0];
            data[plane + i] = (g - DET_MEAN[1]) / DET_STD[1];
            data[2 * plane + i] = (r - DET_MEAN[2]) / DET_STD[2];
        }
        float[][] map;
        try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), new long[]{1, 3, rh, rw});
             OrtSession.Result result = detector.run(Map.of(detector.getInputNames().iterator().next(), input))) {
            map = ((float[][][][]) result.get(0).getValue())[0][0];
        }
        double sx = (double) w / map[0].length;
        double sy = (double) h / map.length;
        List<int[]> boxes = new ArrayList<>();
        for (double[] region : regions(map, DET_THRESHOLD, BOX_THRESHOLD)) {
            double bw = region[2] - region[0] + 1;
            double bh = region[3] - region[1] + 1;
            double grow = bw * bh * UNCLIP_RATIO / (2 * (bw + bh));
            int left = clamp((int) Math.floor((region[0] - grow) * sx), w);
            int top = clamp((int) Math.floor((region[1] - grow) * sy), h);
            int right = clamp((int) Math.ceil((region[2] + 1 + grow) * sx), w);
            int bottom = clamp((int) Math.ceil((region[3] + 1 + grow) * sy), h);
            if (right - left >= 4 && bottom - top >= 4) {
                boxes.add(new int[]{left, top, right, bottom});
            }
        }
        return boxes;
    }

    /**
     * The connected regions (4-neighbours) of the map above threshold whose mean probability is at least
     * boxThreshold, as {left, top, right, bottom} in the map's pixels (inclusive).
     */
    static List<double[]> regions(float[][] map, float threshold, double boxThreshold) {
        int h = map.length;
        int w = h == 0 ? 0 : map[0].length;
        boolean[] seen = new boolean[w * h];
        int[] stack = new int[w * h];
        List<double[]> regions = new ArrayList<>();
        for (int start = 0; start < w * h; start++) {
            if (seen[start] || map[start / w][start % w] <= threshold) {
                continue;
            }
            int top = 0;
            stack[top++] = start;
            seen[start] = true;
            int minX = w;
            int minY = h;
            int maxX = -1;
            int maxY = -1;
            int count = 0;
            double sum = 0;
            while (top > 0) {
                int at = stack[--top];
                int x = at % w;
                int y = at / w;
                count++;
                sum += map[y][x];
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                if (x > 0) {
                    top = push(map, seen, stack, top, at - 1, x - 1, y, threshold);
                }
                if (x < w - 1) {
                    top = push(map, seen, stack, top, at + 1, x + 1, y, threshold);
                }
                if (y > 0) {
                    top = push(map, seen, stack, top, at - w, x, y - 1, threshold);
                }
                if (y < h - 1) {
                    top = push(map, seen, stack, top, at + w, x, y + 1, threshold);
                }
            }
            if (count >= MIN_REGION_PIXELS && Math.min(maxX - minX, maxY - minY) >= 2 && sum / count >= boxThreshold) {
                regions.add(new double[]{minX, minY, maxX, maxY});
            }
        }
        return regions;
    }

    private static int push(float[][] map, boolean[] seen, int[] stack, int top, int index, int x, int y,
                            float threshold) {
        if (!seen[index] && map[y][x] > threshold) {
            seen[index] = true;
            stack[top++] = index;
        }
        return top;
    }

    // --- Recognition -----------------------------------------------------------------------------------------------

    private TextBox recognize(BufferedImage image, int[] box) throws OrtException {
        int cw = box[2] - box[0];
        int ch = box[3] - box[1];
        BufferedImage crop = image.getSubimage(box[0], box[1], cw, ch);
        int width = Math.min(REC_MAX_WIDTH, Math.max(1, (int) Math.ceil(REC_HEIGHT * (double) cw / ch)));
        int padded = Math.max(REC_MIN_WIDTH, width);
        BufferedImage line = resize(crop, width, REC_HEIGHT);
        int[] pixels = line.getRGB(0, 0, width, REC_HEIGHT, null, 0, width);
        int plane = padded * REC_HEIGHT;
        float[] data = new float[3 * plane];
        for (int y = 0; y < REC_HEIGHT; y++) {
            for (int x = 0; x < width; x++) {
                int p = pixels[y * width + x];
                int i = y * padded + x;
                data[i] = (p & 0xff) / 127.5f - 1f;
                data[plane + i] = ((p >> 8) & 0xff) / 127.5f - 1f;
                data[2 * plane + i] = ((p >> 16) & 0xff) / 127.5f - 1f;
            }
        }
        float[][] steps;
        try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(data),
                new long[]{1, 3, REC_HEIGHT, padded});
             OrtSession.Result result = recognizer.run(Map.of(recognizer.getInputNames().iterator().next(), input))) {
            steps = ((float[][][]) result.get(0).getValue())[0];
        }
        Decoded decoded = decode(steps, characters);
        if (decoded.text().isBlank()) {
            return null;
        }
        return new TextBox(box[0], box[1], box[2], box[3], decoded.text().strip(), decoded.confidence());
    }

    record Decoded(String text, double confidence) {
    }

    /**
     * CTC greedy decoding: the most probable class at each step; class 0 is the blank, class i the (i-1)th character
     * (a space after the list when the model has one class more). Repeats without a blank between are one character.
     */
    static Decoded decode(float[][] steps, List<String> characters) {
        StringBuilder text = new StringBuilder();
        double sum = 0;
        int count = 0;
        int previous = -1;
        for (float[] step : steps) {
            int best = 0;
            for (int c = 1; c < step.length; c++) {
                if (step[c] > step[best]) {
                    best = c;
                }
            }
            if (best != 0 && best != previous) {
                int index = best - 1;
                text.append(index < characters.size() ? characters.get(index) : " ");
                sum += step[best];
                count++;
            }
            previous = best;
        }
        return new Decoded(text.toString(), count == 0 ? 0 : sum / count);
    }

    // --- Files and images ------------------------------------------------------------------------------------------

    /**
     * The characters, in the model's order: an inference.yml's PostProcess.character_dict (every value read as text),
     * else one character per line.
     */
    static List<String> characters(Path dictionary) throws IOException {
        String name = dictionary.getFileName().toString().toLowerCase();
        if (name.endsWith(".yml") || name.endsWith(".yaml")) {
            LoaderOptions loader = new LoaderOptions();
            // Every scalar as text: "1", "yes" and "~" are characters here, not a number, true or null.
            Yaml yaml = new Yaml(new SafeConstructor(loader), new Representer(new DumperOptions()),
                    new DumperOptions(), loader, new Resolver() {
                        @Override
                        protected void addImplicitResolvers() {
                        }
                    });
            Object root = yaml.load(Files.readString(dictionary, StandardCharsets.UTF_8));
            if (root instanceof Map<?, ?> map && map.get("PostProcess") instanceof Map<?, ?> post
                    && post.get("character_dict") instanceof List<?> list) {
                return list.stream().map(String::valueOf).toList();
            }
            throw new IOException("No PostProcess.character_dict in " + dictionary.getFileName());
        }
        return Files.readAllLines(dictionary, StandardCharsets.UTF_8).stream()
                .map(line -> line.replace("\r", "").replace("\n", ""))
                .toList();
    }

    private static BufferedImage rgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(image, 0, 0, java.awt.Color.WHITE, null);
        g.dispose();
        return copy;
    }

    private static BufferedImage resize(BufferedImage image, int width, int height) {
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(image, 0, 0, width, height, null);
        g.dispose();
        return resized;
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }
}
