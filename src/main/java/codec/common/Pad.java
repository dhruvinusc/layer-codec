package codec.common;

import codec.common.Types.Frame;

/**
 * Edge-replicate padding to whole 16x16 macroblocks, and the matching crop (03 D5).
 *
 * <p>Right and bottom only, so pixel (x, y) keeps its coordinates. Replication instead of black:
 * a black border would create a false strong edge that the DCT spends bits on and block matching
 * can lock onto. The true size travels in the .cmp header and the decoder crops.
 */
public final class Pad {

    private Pad() {
    }

    /** Returns a padded copy of {@code src}; a plain copy when no padding is needed. */
    public static Frame toMacroblocks(Frame src) {
        return pad(src, Types.paddedSize(src.width()), Types.paddedSize(src.height()));
    }

    public static Frame pad(Frame src, int paddedW, int paddedH) {
        if (paddedW < src.width() || paddedH < src.height()) {
            throw new IllegalArgumentException("padded size smaller than frame");
        }
        Frame dst = Frame.allocate(paddedW, paddedH);
        padPlane(src.r(), src.width(), src.height(), dst.r(), paddedW, paddedH);
        padPlane(src.g(), src.width(), src.height(), dst.g(), paddedW, paddedH);
        padPlane(src.b(), src.width(), src.height(), dst.b(), paddedW, paddedH);
        return dst;
    }

    /** Copies a w x h plane into the top-left of a pw x ph plane, repeating the last column and row. */
    public static void padPlane(int[] src, int w, int h, int[] dst, int pw, int ph) {
        for (int y = 0; y < h; y++) {
            int s = y * w;
            int d = y * pw;
            System.arraycopy(src, s, dst, d, w);
            int edge = src[s + w - 1];
            for (int x = w; x < pw; x++) {
                dst[d + x] = edge;
            }
        }
        int lastRow = (h - 1) * pw;
        for (int y = h; y < ph; y++) {
            System.arraycopy(dst, lastRow, dst, y * pw, pw);
        }
    }

    /** Inverse of {@link #pad}: keeps the top-left w x h region. */
    public static Frame crop(Frame src, int w, int h) {
        if (w > src.width() || h > src.height()) {
            throw new IllegalArgumentException("crop larger than frame");
        }
        Frame dst = Frame.allocate(w, h);
        for (int y = 0; y < h; y++) {
            System.arraycopy(src.r(), y * src.width(), dst.r(), y * w, w);
            System.arraycopy(src.g(), y * src.width(), dst.g(), y * w, w);
            System.arraycopy(src.b(), y * src.width(), dst.b(), y * w, w);
        }
        return dst;
    }
}
