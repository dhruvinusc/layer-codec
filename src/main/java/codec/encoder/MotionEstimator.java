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

    /**
     * Settings for the hierarchical search (03 D2). Kept here, not in the frozen Config.
     *
     * @param levels      pyramid levels including full resolution (3 = full, half, quarter)
     * @param coarseRange exhaustive range at the coarsest level, in coarse pixels (8 at 1/4 = 32 px)
     * @param refine      refinement range at every finer level, in that level's pixels
     * @param lambda      smoothness weight in SAD units per full-resolution pixel of distance
     *                    from the median of the left, top and top-right vectors
     */
    public record HierParams(int levels, int coarseRange, int refine, int lambda) {
        public static final HierParams DEFAULT = new HierParams(3, 8, 2, 4);

        public HierParams {
            if (levels < 1 || levels > 4 || coarseRange < 1 || refine < 1 || lambda < 0) {
                throw new IllegalArgumentException("bad hierarchical params");
            }
        }
    }

    /**
     * Fast default search (03 D2): exhaustive at the coarsest pyramid level, then +/-refine at each
     * finer level, then a few extra candidates at full resolution (zero, left, top, top-right,
     * the co-located vector of the previous frame, and the previous camera motion).
     *
     * <p>Cost = SAD + lambda * L1 distance to the median of the left, top and top-right vectors.
     * The penalty only matters when two positions match almost equally well, which is exactly the
     * case on bricks, railings and stripes, where a wrong period-shifted match is otherwise picked.
     * Blocks are visited in scan order so those neighbours are already known: single-threaded and
     * deterministic. {@code sadBest} holds the pure SAD of the chosen vector (no penalty).
     *
     * @param prevField vectors of the previous frame pair, or null
     * @param camDx     camera motion hint in the D12 convention (0 if unknown)
     * @param camDy     camera motion hint
     */
    public static Result hierarchical(int[] cur, int[] prev, int w, int h, HierParams p,
                                      MotionField prevField, int camDx, int camDy) {
        checkInputs(cur, prev, w, h, 0);
        int levels = p.levels();
        int[][] pc = new int[levels][];
        int[][] pp = new int[levels][];
        pc[0] = cur;
        pp[0] = prev;
        for (int k = 1; k < levels; k++) {
            pc[k] = half(pc[k - 1], w >> (k - 1), h >> (k - 1));
            pp[k] = half(pp[k - 1], w >> (k - 1), h >> (k - 1));
        }
        int cols = w / MB;
        int rows = h / MB;
        int n = cols * rows;
        short[] dx = new short[n];
        short[] dy = new short[n];
        int[] sadBest = new int[n];
        int[] sadZero = new int[n];
        for (int by = 0; by < rows; by++) {
            for (int bx = 0; bx < cols; bx++) {
                int i = by * cols + bx;
                int predX = median3(at(dx, cols, bx - 1, by), at(dx, cols, bx, by - 1), at(dx, cols, bx + 1, by - 1));
                int predY = median3(at(dy, cols, bx - 1, by), at(dy, cols, bx, by - 1), at(dy, cols, bx + 1, by - 1));
                // Coarse: exhaustive around zero, keep the best few starts (a single coarse winner
                // can be a local minimum once textures blur in the small image).
                int top = levels - 1;
                int[] starts = coarseStarts(pc[top], pp[top], w >> top, h >> top, top, bx, by, p.coarseRange(), predX, predY, p.lambda());
                Best b = new Best();
                for (int s = 0; s < starts.length; s += 2) {
                    Best r = new Best();
                    r.vx = starts[s];
                    r.vy = starts[s + 1];
                    // Finer levels: refine around the doubled vector.
                    for (int k = top - 1; k >= 0; k--) {
                        int sx = r.vx * 2;
                        int sy = r.vy * 2;
                        r = new Best();
                        searchWindow(pc[k], pp[k], w >> k, h >> k, k, bx, by, sx, sy, p.refine(), predX, predY, p.lambda(), r);
                    }
                    if (top == 0) {
                        tryPoint(cur, prev, w, h, bx, by, r.vx, r.vy, predX, predY, p.lambda(), r);
                    }
                    consider(b, r.vx, r.vy, r.sad, r.cost);
                }
                // Extra candidates at full resolution.
                tryPoint(cur, prev, w, h, bx, by, 0, 0, predX, predY, p.lambda(), b);
                tryPoint(cur, prev, w, h, bx, by, predX, predY, predX, predY, p.lambda(), b);
                tryPoint(cur, prev, w, h, bx, by, at(dx, cols, bx - 1, by), at(dy, cols, bx - 1, by), predX, predY, p.lambda(), b);
                tryPoint(cur, prev, w, h, bx, by, at(dx, cols, bx, by - 1), at(dy, cols, bx, by - 1), predX, predY, p.lambda(), b);
                if (prevField != null) {
                    tryPoint(cur, prev, w, h, bx, by, prevField.dx()[i], prevField.dy()[i], predX, predY, p.lambda(), b);
                }
                tryPoint(cur, prev, w, h, bx, by, camDx, camDy, predX, predY, p.lambda(), b);
                // Polish: +/-1 around whichever start won, so a good candidate is not left 1 px off.
                searchWindow(cur, prev, w, h, 0, bx, by, b.vx, b.vy, 1, predX, predY, p.lambda(), b);
                dx[i] = (short) b.vx;
                dy[i] = (short) b.vy;
                sadBest[i] = b.sad;
                sadZero[i] = sad(cur, prev, w, bx * MB, by * MB, bx * MB, by * MB, Integer.MAX_VALUE);
            }
        }
        return new Result(new MotionField(cols, rows, dx, dy), sadBest, sadZero);
    }

    /** Best candidate so far at one level: vector in that level's pixels, its cost and pure SAD. */
    private static final class Best {
        int vx;
        int vy;
        long cost = Long.MAX_VALUE;
        int sad = Integer.MAX_VALUE;
        int norm = Integer.MAX_VALUE;
    }

    /** Exhaustive window at level k around (sx, sy), in level-k pixels. Same tie-break as full search. */
    private static void searchWindow(int[] cur, int[] prev, int w, int h, int k, int bx, int by,
                                     int sx, int sy, int range, int predX, int predY, int lambda, Best b) {
        int bs = MB >> k;
        int x0 = bx * bs;
        int y0 = by * bs;
        int dxMin = Math.max(sx - range, -x0);
        int dxMax = Math.min(sx + range, w - bs - x0);
        int dyMin = Math.max(sy - range, -y0);
        int dyMax = Math.min(sy + range, h - bs - y0);
        // lambda is per full-resolution pixel for a 256-pixel block; scale to this level's block and pixel size.
        long lam = (long) lambda * bs * bs * (1L << k) / PIXELS;
        for (int vy = dyMin; vy <= dyMax; vy++) {
            for (int vx = dxMin; vx <= dxMax; vx++) {
                int s = sadN(cur, prev, w, bs, x0, y0, x0 + vx, y0 + vy);
                long dist = Math.abs((vx << k) - predX) + Math.abs((vy << k) - predY);
                consider(b, vx, vy, s, s + lam * dist / (1L << k));
            }
        }
    }

    /** How many coarse winners are refined separately (P1-D6). */
    static final int COARSE_STARTS = 3;

    /**
     * Exhaustive coarse search around zero; returns up to {@link #COARSE_STARTS} best vectors
     * (x, y pairs, coarse pixels), best first, same cost and tie-break as everywhere else.
     */
    private static int[] coarseStarts(int[] cur, int[] prev, int w, int h, int k, int bx, int by,
                                      int range, int predX, int predY, int lambda) {
        int bs = MB >> k;
        int x0 = bx * bs;
        int y0 = by * bs;
        long lam = (long) lambda * bs * bs * (1L << k) / PIXELS;
        long[] cost = new long[COARSE_STARTS];
        int[] norm = new int[COARSE_STARTS];
        int[] out = new int[2 * COARSE_STARTS];
        java.util.Arrays.fill(cost, Long.MAX_VALUE);
        int found = 0;
        for (int vy = Math.max(-range, -y0); vy <= Math.min(range, h - bs - y0); vy++) {
            for (int vx = Math.max(-range, -x0); vx <= Math.min(range, w - bs - x0); vx++) {
                int s = sadN(cur, prev, w, bs, x0, y0, x0 + vx, y0 + vy);
                long c = s + lam * (Math.abs((vx << k) - predX) + Math.abs((vy << k) - predY)) / (1L << k);
                int nm = Math.abs(vx) + Math.abs(vy);
                int pos = COARSE_STARTS;
                while (pos > 0 && (c < cost[pos - 1] || (c == cost[pos - 1] && nm < norm[pos - 1]))) {
                    pos--;
                }
                if (pos < COARSE_STARTS) {
                    for (int j = COARSE_STARTS - 1; j > pos; j--) {
                        cost[j] = cost[j - 1];
                        norm[j] = norm[j - 1];
                        out[2 * j] = out[2 * j - 2];
                        out[2 * j + 1] = out[2 * j - 1];
                    }
                    cost[pos] = c;
                    norm[pos] = nm;
                    out[2 * pos] = vx;
                    out[2 * pos + 1] = vy;
                    found = Math.min(COARSE_STARTS, found + 1);
                }
            }
        }
        return java.util.Arrays.copyOf(out, 2 * found);
    }

    /** One full-resolution candidate, skipped if it leaves the frame. */
    private static void tryPoint(int[] cur, int[] prev, int w, int h, int bx, int by, int vx, int vy,
                                 int predX, int predY, int lambda, Best b) {
        int x0 = bx * MB;
        int y0 = by * MB;
        if (x0 + vx < 0 || y0 + vy < 0 || x0 + vx + MB > w || y0 + vy + MB > h) {
            return;
        }
        int s = sad(cur, prev, w, x0, y0, x0 + vx, y0 + vy, Integer.MAX_VALUE);
        consider(b, vx, vy, s, s + (long) lambda * (Math.abs(vx - predX) + Math.abs(vy - predY)));
    }

    private static void consider(Best b, int vx, int vy, int sad, long cost) {
        int norm = Math.abs(vx) + Math.abs(vy);
        if (cost < b.cost || (cost == b.cost && norm < b.norm)) {
            b.cost = cost;
            b.sad = sad;
            b.norm = norm;
            b.vx = vx;
            b.vy = vy;
        }
    }

    /** SAD of a bs x bs block, no early exit (costs include a penalty, so a raw-SAD bound is not valid). */
    private static int sadN(int[] cur, int[] prev, int w, int bs, int cx, int cy, int px, int py) {
        int sum = 0;
        for (int row = 0; row < bs; row++) {
            int c = (cy + row) * w + cx;
            int q = (py + row) * w + px;
            for (int k = 0; k < bs; k++) {
                sum += Math.abs(cur[c + k] - prev[q + k]);
            }
        }
        return sum;
    }

    /** 2x2 average with rounding: the next pyramid level. */
    static int[] half(int[] src, int w, int h) {
        int hw = w / 2;
        int hh = h / 2;
        int[] dst = new int[hw * hh];
        for (int y = 0; y < hh; y++) {
            int a = 2 * y * w;
            int c = a + w;
            for (int x = 0; x < hw; x++) {
                dst[y * hw + x] = (src[a + 2 * x] + src[a + 2 * x + 1] + src[c + 2 * x] + src[c + 2 * x + 1] + 2) >> 2;
            }
        }
        return dst;
    }

    /** Vector component of a neighbour, 0 outside the grid or not yet computed (below the current row). */
    private static int at(short[] v, int cols, int bx, int by) {
        if (bx < 0 || by < 0 || bx >= cols) {
            return 0;
        }
        return v[by * cols + bx];
    }

    static int median3(int a, int b, int c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
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
