package codec.common;

import codec.common.Types.Frame;

/**
 * RGB to luma for motion search (brief: search on one component, Y preferred).
 *
 * <p>BT.601 weights 0.299, 0.587, 0.114 scaled by 256 to 77, 150, 29. They sum to exactly 256,
 * so gray stays gray (R = G = B gives Y = R) and white stays 255. Integer math: deterministic on
 * every machine and faster than doubles. Error against the float formula is at most 1 level.
 */
public final class ColorConv {

    static final int WR = 77;
    static final int WG = 150;
    static final int WB = 29;

    private ColorConv() {
    }

    public static int luma(int r, int g, int b) {
        return (WR * r + WG * g + WB * b + 128) >> 8;
    }

    /** Writes the Y plane of {@code f} into {@code y} (length width * height). */
    public static void luma(Frame f, int[] y) {
        if (y.length != f.width() * f.height()) {
            throw new IllegalArgumentException("Y plane size does not match frame");
        }
        int[] r = f.r();
        int[] g = f.g();
        int[] b = f.b();
        for (int i = 0; i < y.length; i++) {
            y[i] = (WR * r[i] + WG * g[i] + WB * b[i] + 128) >> 8;
        }
    }

    public static int[] luma(Frame f) {
        int[] y = new int[f.width() * f.height()];
        luma(f, y);
        return y;
    }
}
