package codec.encoder;

import codec.common.Types;
import codec.common.Types.Labels;
import codec.common.Types.MotionField;

/**
 * Foreground/background labelling from motion only (03 D3). Built up step by step in P1:
 * reliability (P1.4) first; camera motion, residuals, grouping, cleaning and temporal voting follow.
 *
 * <p>All thresholds live in {@link SegConfig} (one record, tuned on all 4 clips together in P1.9).
 * The frozen {@code codec.common.Config} is not touched (P1-D5).
 */
public final class Segmenter {

    private Segmenter() {
    }

    /**
     * Segmentation thresholds.
     *
     * @param minTextureVar a block needs at least this Y variance (std dev 10 at 100) to be matched
     *                      reliably; flatter blocks (sky, road, padding) match many positions
     * @param maxMatchMad   a block whose best match still differs by more than this many levels per
     *                      pixel did not really find itself in the previous frame (occlusion, blur)
     */
    public record SegConfig(int minTextureVar, int maxMatchMad) {
        public static final SegConfig DEFAULT = new SegConfig(100, 12);
    }

    /**
     * P1.4: true where a block's vector can be trusted. Unreliable blocks are not evidence for or
     * against motion; later steps give them their neighbours' label.
     */
    public static boolean[] reliable(int[] y, int w, int h, MotionEstimator.Result me, SegConfig c) {
        int cols = w / Types.MB;
        int rows = h / Types.MB;
        boolean[] ok = new boolean[cols * rows];
        long maxSad = (long) c.maxMatchMad() * Types.MB * Types.MB;
        for (int i = 0; i < ok.length; i++) {
            int x0 = (i % cols) * Types.MB;
            int y0 = (i / cols) * Types.MB;
            ok[i] = blockVariance(y, w, x0, y0) >= c.minTextureVar() && me.sadBest()[i] <= maxSad;
        }
        return ok;
    }

    /** Naive baseline (05, P0-D5): a textured block is foreground if its residual exceeds this. */
    public static final double NAIVE_RESIDUAL_PX = 3.0;
    /** Naive baseline: connected groups smaller than this many macroblocks are dropped. */
    public static final int NAIVE_MIN_BLOCKS = 4;

    /**
     * Residual motion per block: block vector minus the camera vector at the block center, so a block
     * that moves exactly with the camera has residual (0, 0). Returns {rx, ry} in pixels.
     */
    public static float[][] residuals(MotionField mv, GlobalMotion.Model cam) {
        int n = mv.cols() * mv.rows();
        float[] rx = new float[n];
        float[] ry = new float[n];
        double[] v = new double[2];
        for (int i = 0; i < n; i++) {
            cam.predict((i % mv.cols()) * Types.MB + Types.MB / 2.0, (i / mv.cols()) * Types.MB + Types.MB / 2.0, v);
            rx[i] = (float) (mv.dx()[i] - v[0]);
            ry[i] = (float) (mv.dy()[i] - v[1]);
        }
        return new float[][] {rx, ry};
    }

    /**
     * The naive baseline P1 must beat: one frame only, a textured block whose residual is larger than
     * {@link #NAIVE_RESIDUAL_PX} is a candidate, and 4-connected groups of at least
     * {@link #NAIVE_MIN_BLOCKS} candidates become foreground. No match-quality gate, no direction
     * consistency, no cleaning, no temporal vote.
     */
    public static Labels naive(int[] y, int w, int h, MotionField mv, GlobalMotion.Model cam, SegConfig c) {
        float[][] r = residuals(mv, cam);
        int cols = mv.cols();
        boolean[] cand = new boolean[cols * mv.rows()];
        double t2 = NAIVE_RESIDUAL_PX * NAIVE_RESIDUAL_PX;
        for (int i = 0; i < cand.length; i++) {
            double m2 = (double) r[0][i] * r[0][i] + (double) r[1][i] * r[1][i];
            cand[i] = m2 > t2 && blockVariance(y, w, (i % cols) * Types.MB, (i / cols) * Types.MB) >= c.minTextureVar();
        }
        byte[] id = new byte[cand.length];
        components(cand, cols, mv.rows(), NAIVE_MIN_BLOCKS, id);
        return new Labels(cols, mv.rows(), id);
    }

    /**
     * 4-connected components of {@code cand} in fixed scan order (breadth first, int queue). Groups
     * of at least {@code minSize} blocks get ids 1, 2, ... (capped at 255; later groups share 255).
     * Returns the number of groups kept.
     */
    static int components(boolean[] cand, int cols, int rows, int minSize, byte[] out) {
        int n = cand.length;
        boolean[] seen = new boolean[n];
        int[] queue = new int[n];
        int kept = 0;
        for (int s = 0; s < n; s++) {
            if (!cand[s] || seen[s]) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = s;
            seen[s] = true;
            while (head < tail) {
                int i = queue[head++];
                int cx = i % cols;
                int cy = i / cols;
                tail = visit(cand, seen, queue, tail, cx > 0 ? i - 1 : -1);
                tail = visit(cand, seen, queue, tail, cx < cols - 1 ? i + 1 : -1);
                tail = visit(cand, seen, queue, tail, cy > 0 ? i - cols : -1);
                tail = visit(cand, seen, queue, tail, cy < rows - 1 ? i + cols : -1);
            }
            if (tail >= minSize) {
                kept++;
                byte label = (byte) Math.min(kept, 255);
                for (int k = 0; k < tail; k++) {
                    out[queue[k]] = label;
                }
            }
        }
        return kept;
    }

    private static int visit(boolean[] cand, boolean[] seen, int[] queue, int tail, int j) {
        if (j >= 0 && cand[j] && !seen[j]) {
            seen[j] = true;
            queue[tail++] = j;
        }
        return tail;
    }

    /** Integer variance of the 16x16 Y block at (x0, y0). */
    public static int blockVariance(int[] y, int w, int x0, int y0) {
        long sum = 0;
        long sq = 0;
        for (int r = 0; r < Types.MB; r++) {
            int row = (y0 + r) * w + x0;
            for (int k = 0; k < Types.MB; k++) {
                int v = y[row + k];
                sum += v;
                sq += (long) v * v;
            }
        }
        int n = Types.MB * Types.MB;
        return (int) ((sq - sum * sum / n) / n);
    }
}
