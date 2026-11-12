package com.nereonix.nereonet_mvp.core.profile;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Profile photos (P31), with the JDK's ImageIO only. Whatever the browser sent, the server makes its own JPEGs:
 *   - the real type is read from the file's first bytes (JPEG or PNG), not from its name or the browser's word;
 *   - a camera photo's EXIF orientation is applied, so it is not shown on its side;
 *   - "large": at most 512 px on the longest side; "small": a 96 x 96 square from the middle, for the round pictures
 *     of lists and headers;
 *   - written as new JPEGs from the pixels only, so nothing of the original's metadata (EXIF, GPS, comments, colour
 *     profiles) is kept. A transparent PNG gets a white background.
 * A large image is read subsampled (never more than about 2048 px on its longest side in memory), so a 5 MB file
 * with huge dimensions cannot exhaust memory.
 */
public final class PhotoImages {

    public static final int LARGE = 512;
    public static final int SMALL = 96;

    private static final int MAX_SIDE = 20_000;
    private static final int READ_TARGET = 1024;
    private static final float QUALITY = 0.85f;

    /** The two JPEGs and the large one's size in pixels. */
    public record Photo(byte[] large, byte[] small, int width, int height) {
    }

    /** Not a JPEG or PNG, or one ImageIO cannot read (damaged, CMYK). */
    public static class UnreadablePhotoException extends Exception {
        public UnreadablePhotoException(String message) {
            super(message);
        }
    }

    private PhotoImages() {
    }

    /** "jpeg", "png", or null for anything else, from the file's signature. */
    static String formatOf(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "jpeg";
        }
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        if (bytes.length >= png.length) {
            for (int i = 0; i < png.length; i++) {
                if (bytes[i] != png[i]) {
                    return null;
                }
            }
            return "png";
        }
        return null;
    }

    public static Photo process(byte[] bytes) throws UnreadablePhotoException {
        String format = formatOf(bytes);
        if (format == null) {
            throw new UnreadablePhotoException("Not a JPEG or PNG file");
        }
        BufferedImage image = read(bytes, format);
        if ("jpeg".equals(format)) {
            image = orient(image, ExifOrientation.of(bytes));
        }
        BufferedImage rgb = onWhite(image);
        BufferedImage large = scale(rgb, LARGE);
        BufferedImage small = square(rgb, SMALL);
        return new Photo(jpeg(large), jpeg(small), large.getWidth(), large.getHeight());
    }

    private static BufferedImage read(byte[] bytes, String format) throws UnreadablePhotoException {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
        if (!readers.hasNext()) {
            throw new UnreadablePhotoException("No reader for " + format);
        }
        ImageReader reader = readers.next();
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(input, true, true); // ignore metadata: never read, never kept
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE) {
                throw new UnreadablePhotoException("Unusable size " + width + "x" + height);
            }
            ImageReadParam param = reader.getDefaultReadParam();
            int step = Math.max(1, Math.max(width, height) / READ_TARGET);
            if (step > 1) {
                param.setSourceSubsampling(step, step, 0, 0);
            }
            BufferedImage image = reader.read(0, param);
            if (image == null) {
                throw new UnreadablePhotoException("Empty image");
            }
            return image;
        } catch (IOException | RuntimeException e) {
            throw new UnreadablePhotoException("Cannot read the image: " + e.getMessage());
        } finally {
            reader.dispose();
        }
    }

    // EXIF orientation 1 to 8: rotate and mirror as the camera meant it. 1 (or unknown) is as stored.
    private static BufferedImage orient(BufferedImage image, int orientation) {
        if (orientation < 2 || orientation > 8) {
            return image;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        boolean swap = orientation >= 5;
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.translate(w, 0); t.scale(-1, 1); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.translate(0, h); t.scale(1, -1); }
            case 5 -> { t.rotate(-Math.PI / 2); t.scale(-1, 1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(-Math.PI / 2); }
            case 8 -> { t.translate(0, w); t.rotate(-Math.PI / 2); }
            default -> { }
        }
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(image, t, null);
        g.dispose();
        return out;
    }

    // RGB on white: JPEG has no transparency.
    private static BufferedImage onWhite(BufferedImage image) {
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return out;
    }

    // At most max px on the longest side, never larger than it was. Halves step by step first, which keeps a good
    // quality with the plain bilinear filter.
    private static BufferedImage scale(BufferedImage image, int max) {
        int longest = Math.max(image.getWidth(), image.getHeight());
        if (longest <= max) {
            return image;
        }
        double ratio = (double) max / longest;
        int width = Math.max(1, (int) Math.round(image.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(image.getHeight() * ratio));
        return resize(image, width, height);
    }

    // The largest square from the middle, then size x size.
    private static BufferedImage square(BufferedImage image, int size) {
        int side = Math.min(image.getWidth(), image.getHeight());
        BufferedImage crop = image.getSubimage((image.getWidth() - side) / 2, (image.getHeight() - side) / 2, side, side);
        return resize(crop, size, size);
    }

    private static BufferedImage resize(BufferedImage image, int width, int height) {
        BufferedImage current = image;
        int w = image.getWidth();
        int h = image.getHeight();
        do {
            w = Math.max(width, w / 2);
            h = Math.max(height, h / 2);
            BufferedImage next = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(current, 0, 0, w, h, null);
            g.dispose();
            current = next;
        } while (w != width || h != height);
        return current;
    }

    // A baseline JPEG from the pixels only: no metadata is passed to the writer, so it writes the JFIF header alone.
    private static byte[] jpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
