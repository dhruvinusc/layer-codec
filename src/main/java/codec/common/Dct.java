package codec.common;

/**
 * Orthonormal 8x8 DCT-II and its inverse (brief part 2, checklist C2).
 *
 * <p>Formula (class notes): F(u,v) = 1/4 C(u) C(v) sum_x sum_y f(x,y) cos((2x+1)u pi/16)
 * cos((2y+1)v pi/16), with C(0) = 1/sqrt(2) and C(k) = 1 otherwise. Orthonormal means the
 * inverse is the transpose, so forward then inverse returns the input exactly (up to double
 * rounding). The DCT loses nothing; quantization is the only lossy step.
 *
 * <p>Layout: coefficients are row-major, {@code coef[v * 8 + u]}, v = vertical frequency (row),
 * u = horizontal frequency (column). Index 0 is DC. No zigzag (baseline, C6).
 *
 * <p>Speed: separable (8 rows then 8 columns = 2 x 512 multiply-adds instead of 4096) with a
 * precomputed cosine table. Callers pass scratch arrays so no block allocates (P3 decodes
 * frames on several threads, so scratch must be per thread; this class holds no mutable state).
 */
public final class Dct {

    /** Pixels are shifted from 0..255 to -128..127 before the transform, as in JPEG. */
    public static final int LEVEL_SHIFT = 128;

    private static final int N = Types.BLK;

    /** BASIS[k * 8 + i] = c(k) * cos((2i + 1) k pi / 16), c(0) = sqrt(1/8), c(k) = sqrt(2/8). */
    private static final double[] BASIS = buildBasis();

    private Dct() {
    }

    private static double[] buildBasis() {
        double[] t = new double[N * N];
        for (int k = 0; k < N; k++) {
            // sqrt(1/8) = 1/2 * 1/sqrt(2) and sqrt(2/8) = 1/2: the 1/4 C(u) C(v) of the formula,
            // split evenly between the row pass and the column pass.
            double c = k == 0 ? Math.sqrt(1.0 / N) : Math.sqrt(2.0 / N);
            for (int i = 0; i < N; i++) {
                t[k * N + i] = c * Math.cos((2 * i + 1) * k * Math.PI / (2.0 * N));
            }
        }
        return t;
    }

    /**
     * Forward DCT of the 8x8 block whose top-left pixel is (x0, y0) in a plane of width
     * {@code stride}. Level shift is applied here.
     *
     * @param out 64 coefficients, row-major
     * @param tmp 64-entry scratch
     */
    public static void forward(int[] plane, int stride, int x0, int y0, double[] out, double[] tmp) {
        // Row pass: tmp[y][u] = sum_x BASIS[u][x] * (f[y][x] - 128)
        for (int y = 0; y < N; y++) {
            int row = (y0 + y) * stride + x0;
            for (int u = 0; u < N; u++) {
                double s = 0;
                for (int x = 0; x < N; x++) {
                    s += BASIS[u * N + x] * (plane[row + x] - LEVEL_SHIFT);
                }
                tmp[y * N + u] = s;
            }
        }
        // Column pass: out[v][u] = sum_y BASIS[v][y] * tmp[y][u]
        for (int v = 0; v < N; v++) {
            for (int u = 0; u < N; u++) {
                double s = 0;
                for (int y = 0; y < N; y++) {
                    s += BASIS[v * N + y] * tmp[y * N + u];
                }
                out[v * N + u] = s;
            }
        }
    }

    /**
     * Inverse DCT. Output is in pixel units with the level shift added back, NOT rounded or
     * clamped (Quant does that, so the round trip test can see the exact values).
     */
    public static void inverse(double[] coef, double[] out, double[] tmp) {
        for (int v = 0; v < N; v++) {
            inverseRow(coef, tmp, v);
        }
        inverseColumns(tmp, out);
    }

    /**
     * Same result as {@link #inverse}, bit for bit, but skips coefficient rows that are all zero.
     * After quantization with a large n most high-frequency rows are zero, so this saves work in
     * the decoder (P0 speed gate: serial 1080p decode is over budget).
     */
    public static void inverseZeroSkip(double[] coef, double[] out, double[] tmp) {
        for (int v = 0; v < N; v++) {
            if (rowIsZero(coef, v)) {
                // Every product in this row would be 0, so the row result is exactly 0.
                for (int x = 0; x < N; x++) {
                    tmp[v * N + x] = 0.0;
                }
            } else {
                inverseRow(coef, tmp, v);
            }
        }
        inverseColumns(tmp, out);
    }

    private static boolean rowIsZero(double[] coef, int v) {
        for (int u = 0; u < N; u++) {
            if (coef[v * N + u] != 0.0) {
                return false;
            }
        }
        return true;
    }

    /** tmp[v][x] = sum_u BASIS[u][x] * coef[v][u] (undo the horizontal transform of row v). */
    private static void inverseRow(double[] coef, double[] tmp, int v) {
        for (int x = 0; x < N; x++) {
            double s = 0;
            for (int u = 0; u < N; u++) {
                s += BASIS[u * N + x] * coef[v * N + u];
            }
            tmp[v * N + x] = s;
        }
    }

    /** out[y][x] = 128 + sum_v BASIS[v][y] * tmp[v][x] (undo the vertical transform). */
    private static void inverseColumns(double[] tmp, double[] out) {
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                double s = 0;
                for (int v = 0; v < N; v++) {
                    s += BASIS[v * N + y] * tmp[v * N + x];
                }
                out[y * N + x] = s + LEVEL_SHIFT;
            }
        }
    }
}
