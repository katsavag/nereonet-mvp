package com.nereonix.nereonet_mvp.core.profile;

/**
 * The EXIF orientation of a JPEG (1 to 8), read from its APP1 segment: how a phone camera says "this photo was taken
 * sideways". ImageIO does not apply it, so PhotoImages does. Only this one value is read; anything unexpected (no
 * EXIF, a damaged segment) counts as 1, "as stored".
 */
final class ExifOrientation {

    private static final int ORIENTATION_TAG = 0x0112;

    private ExifOrientation() {
    }

    static int of(byte[] jpeg) {
        try {
            int i = 2; // after SOI
            while (i + 4 <= jpeg.length) {
                if ((jpeg[i] & 0xFF) != 0xFF) {
                    return 1;
                }
                int marker = jpeg[i + 1] & 0xFF;
                if (marker == 0xDA || marker == 0xD9) { // start of scan, end of image: no more metadata
                    return 1;
                }
                int length = u16(jpeg, i + 2, true);
                if (length < 2) {
                    return 1;
                }
                int data = i + 4;
                if (marker == 0xE1 && data + 6 <= jpeg.length && jpeg[data] == 'E' && jpeg[data + 1] == 'x'
                        && jpeg[data + 2] == 'i' && jpeg[data + 3] == 'f' && jpeg[data + 4] == 0 && jpeg[data + 5] == 0) {
                    return fromTiff(jpeg, data + 6, Math.min(jpeg.length, i + 2 + length));
                }
                i += 2 + length;
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            return 1;
        }
        return 1;
    }

    // The TIFF header and IFD0 of the EXIF segment: [start, end).
    private static int fromTiff(byte[] b, int start, int end) {
        if (start + 8 > end) {
            return 1;
        }
        boolean bigEndian;
        if (b[start] == 'M' && b[start + 1] == 'M') {
            bigEndian = true;
        } else if (b[start] == 'I' && b[start + 1] == 'I') {
            bigEndian = false;
        } else {
            return 1;
        }
        long ifd = u32(b, start + 4, bigEndian);
        if (ifd < 8 || start + ifd + 2 > end) {
            return 1;
        }
        int entries = start + (int) ifd;
        int count = u16(b, entries, bigEndian);
        for (int n = 0; n < count; n++) {
            int entry = entries + 2 + n * 12;
            if (entry + 12 > end) {
                return 1;
            }
            if (u16(b, entry, bigEndian) == ORIENTATION_TAG) {
                int value = u16(b, entry + 8, bigEndian);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static int u16(byte[] b, int i, boolean bigEndian) {
        int first = b[i] & 0xFF;
        int second = b[i + 1] & 0xFF;
        return bigEndian ? (first << 8) | second : (second << 8) | first;
    }

    private static long u32(byte[] b, int i, boolean bigEndian) {
        long value = 0;
        for (int k = 0; k < 4; k++) {
            int index = bigEndian ? i + k : i + 3 - k;
            value = (value << 8) | (b[index] & 0xFF);
        }
        return value;
    }
}
