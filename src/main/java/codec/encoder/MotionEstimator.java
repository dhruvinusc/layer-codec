package codec.encoder;

import codec.common.Types;
import codec.common.Types.MotionField;

/**
 * Block motion estimation on the Y plane, 16x16 macroblocks, reference = previous frame.
 *
 * <p>Vector convention (03 D12): the block at (x, y) in frame t best matches the block at
 * (x + dx, y + dy) in frame t-1.
 *
 * <p>Matching cost is SAD (sum of absolute differences over 256 pixels). The brief asks for MAD;
 * MAD = SAD / 256 is SAD divided by a constant, so both pick the same vector. SAD stays an exact
 * integer, which keeps results deterministic.
 */
public final class MotionEstimator {

    private static final int MB = Types.MB;
    private static final int PIXELS = MB * MB;

    private MotionEstimator() {
    }

    /**
     * Motion vectors plus the matching cost behind each one.
     *
     * @param field   one vector per macroblock
     * @param sadBest SAD at the chosen vector (how well the block matched)
     * @param sadZero SAD at (0, 0) (how much the block changed if nothing moved); later used for
     *                reliability and as a cheap "did anything happen here" signal
     */
    public record Result(MotionField field, int[] sadBest, int[] sadZero) {
        /** The brief's metric for block {@code i}: mean absolute difference per pixel. */
        public double mad(int i) {
            return sadBest[i] / (double) PIXELS;
        }
    }

    /**
     * Exhaustive search: tries every offset with |dx|, |dy| &lt;= range that keeps the reference
     * block inside the frame. Slow but simple, so it is the reference the fast search is checked
     * against (P1.3).
     *
     * <p>Tie-break: lower SAD wins; on equal SAD the smaller |dx| + |dy| wins; if still equal the
     * first in scan order (dy, then dx, ascending). (0, 0) is the starting best, so a flat block
     * always reports (0, 0) rather than an arbitrary offset.
     *
     * @param cur  Y plane of frame t, values 0..255, row-major
     * @param prev Y plane of frame t-1, same size
     * @param w    padded width, a multiple of 16
     * @param h    padded height, a multiple of 16
     */
    public static Result fullSearch(int[] cur, int[] prev, int w, int h, int range) {
        checkInputs(cur, prev, w, h, range);
        int cols = w / MB;
        int rows = h / MB;
        int n = cols * rows;
        short[] dx = new short[n];
        short[] dy = new short[n];
        int[] sadBest = new int[n];
        int[] sadZero = new int[n];
        for (int by = 0; by < rows; by++) {
            for (int bx = 0; bx < cols; bx++) {
                searchBlock(cur, prev, w, h, range, bx, by, by * cols + bx, dx, dy, sadBest, sadZero);
            }
        }
        return new Result(new MotionField(cols, rows, dx, dy), sadBest, sadZero);
    }

    private static void searchBlock(int[] cur, int[] prev, int w, int h, int range, int bx, int by,
                                    int i, short[] outDx, short[] outDy, int[] sadBest, int[] sadZero) {
        int x0 = bx * MB;
        int y0 = by * MB;
        // Clamp the window so the reference block never leaves the padded frame.
        int dxMin = Math.max(-range, -x0);
        int dxMax = Math.min(range, w - MB - x0);
        int dyMin = Math.max(-range, -y0);
        int dyMax = Math.min(range, h - MB - y0);

        int zero = sad(cur, prev, w, x0, y0, x0, y0, Integer.MAX_VALUE);
        int best = zero;
        int bestNorm = 0;
        int bestDx = 0;
        int bestDy = 0;
        for (int vy = dyMin; vy <= dyMax; vy++) {
            for (int vx = dxMin; vx <= dxMax; vx++) {
                int norm = Math.abs(vx) + Math.abs(vy);
                if (norm == 0) {
                    continue;
                }
                int s = sad(cur, prev, w, x0, y0, x0 + vx, y0 + vy, best);
                if (s < best || (s == best && norm < bestNorm)) {
                    best = s;
                    bestNorm = norm;
                    bestDx = vx;
                    bestDy = vy;
                }
            }
        }
        outDx[i] = (short) bestDx;
        outDy[i] = (short) bestDy;
        sadBest[i] = best;
        sadZero[i] = zero;
    }

    /**
     * SAD between the 16x16 block at (cx, cy) in {@code cur} and the one at (px, py) in
     * {@code prev}. Stops early once the running sum exceeds {@code limit}: that candidate can
     * no longer win, and the returned value is then only known to be greater than {@code limit}.
     * Stopping only when strictly greater keeps equal-SAD candidates exact for the tie-break.
     */
    static int sad(int[] cur, int[] prev, int w, int cx, int cy, int px, int py, int limit) {
        int sum = 0;
        for (int row = 0; row < MB; row++) {
            int c = (cy + row) * w + cx;
            int p = (py + row) * w + px;
            for (int k = 0; k < MB; k++) {
                sum += Math.abs(cur[c + k] - prev[p + k]);
            }
            if (sum > limit) {
                return sum;
            }
        }
        return sum;
    }

    private static void checkInputs(int[] cur, int[] prev, int w, int h, int range) {
        if (w <= 0 || h <= 0 || w % MB != 0 || h % MB != 0) {
            throw new IllegalArgumentException("frame must be padded to multiples of 16, got " + w + "x" + h);
        }
        if (cur.length != w * h || prev.length != w * h) {
            throw new IllegalArgumentException("plane length does not match " + w + "x" + h);
        }
        if (range < 0) {
            throw new IllegalArgumentException("range must be >= 0, got " + range);
        }
    }
}
