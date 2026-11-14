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
     * @param minTextureVar  a block needs at least this Y variance (std dev 10 at 100) to be matched
     *                       reliably; flatter blocks (sky, road, padding) match many positions
     * @param maxMatchMad    a block whose best match still differs by more than this many levels per
     *                       pixel did not really find itself in the previous frame (occlusion, blur)
     * @param candMinVar     a block may be a foreground candidate with far less texture than the camera
     *                       fit needs: inside a dark jacket the smoothness penalty (lambda) already gives
     *                       a flat block its neighbours' vector, so its residual is still meaningful
     * @param camSlackMad    a block is "explained by the camera" if the best match within 1 px of the
     *                       camera's vector is worse than its own best match by at most this many levels
     *                       per pixel ...
     * @param camSlackRel    ... or by at most this share of its own best match error
     * @param camWindow      search radius in pixels around the rounded camera vector for that test
     *                       (0 = the rounded vector only; 1 = its 3x3 neighbourhood)
     * @param resPx          an eligible block whose residual (vector minus camera) is longer than this
     *                       is a foreground candidate
     * @param consPx         two neighbouring candidates join one region if their residuals differ by at
     *                       most this much ...
     * @param consMinMag     ... or if both residuals are at least this long ...
     * @param consAngleDeg   ... and point within this angle of each other (fast movers vary in size)
     * @param minRegionBlocks a region needs at least this many macroblocks ...
     * @param minRegionFrac  ... and at least this share of all macroblocks (0.002 = 16 blocks at 1080p)
     * @param maxSpreadPx    a region whose residuals spread more than this (median distance from the
     *                       region's median residual) is noise, not one moving object
     * @param dilate         P1.7: grow every region by one block (4-neighbours) after filling
     */
    public record SegConfig(int minTextureVar, int maxMatchMad, int candMinVar, double camSlackMad, double camSlackRel, int camWindow, double resPx, double consPx,
                            double consMinMag, double consAngleDeg, int minRegionBlocks,
                            double minRegionFrac, double maxSpreadPx, boolean dilate) {
        public static final SegConfig DEFAULT = new SegConfig(100, 12, 20, 1.0, 0.15, 1, 2.5, 2.0, 4.0, 30.0, 4, 0.002, 3.0, false);
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
     * P1.6: blocks allowed to be foreground candidates: a good match (MAD within maxMatchMad) and at
     * least candMinVar texture. Looser than {@link #reliable}, which feeds the camera fit, because a
     * moving person often wears flat clothing; the direction-consistency rule filters the extra noise.
     */
    public static boolean[] eligible(int[] y, int w, int h, MotionEstimator.Result me, SegConfig c) {
        int cols = w / Types.MB;
        boolean[] ok = new boolean[cols * (h / Types.MB)];
        long maxSad = (long) c.maxMatchMad() * Types.MB * Types.MB;
        for (int i = 0; i < ok.length; i++) {
            ok[i] = me.sadBest()[i] <= maxSad
                    && blockVariance(y, w, (i % cols) * Types.MB, (i / cols) * Types.MB) >= c.candMinVar();
        }
        return ok;
    }

    /**
     * P1.6: true where the camera's own motion explains the block almost as well as the block's best
     * vector. On a repeating brick wall the search can lock onto a whole patch of wrong but mutually
     * consistent vectors; the camera's vector matches those blocks about as well, so they are
     * background. A walker's blocks match badly at the camera's vector, so they stay candidates.
     * The camera vector is rounded; with camWindow 1 the best of its 3x3 neighbourhood is used.
     */
    public static boolean[] cameraExplains(int[] cur, int[] prev, int w, int h, MotionEstimator.Result me,
                                           GlobalMotion.Model cam, SegConfig c) {
        int cols = w / Types.MB;
        boolean[] out = new boolean[cols * (h / Types.MB)];
        double[] v = new double[2];
        for (int i = 0; i < out.length; i++) {
            int x0 = (i % cols) * Types.MB;
            int y0 = (i / cols) * Types.MB;
            cam.predict(x0 + Types.MB / 2.0, y0 + Types.MB / 2.0, v);
            int vx = (int) Math.round(v[0]);
            int vy = (int) Math.round(v[1]);
            int best = me.sadBest()[i];
            int slack = (int) Math.max(c.camSlackMad() * Types.MB * Types.MB, c.camSlackRel() * best);
            int limit = best + slack;
            boolean explained = false;
            int r = c.camWindow();
            for (int oy = -r; oy <= r && !explained; oy++) {
                for (int ox = -r; ox <= r && !explained; ox++) {
                    int px = x0 + vx + ox;
                    int py = y0 + vy + oy;
                    if (px >= 0 && py >= 0 && px + Types.MB <= w && py + Types.MB <= h) {
                        explained = MotionEstimator.sad(cur, prev, w, x0, y0, px, py, limit) <= limit;
                    }
                }
            }
            out[i] = explained;
        }
        return out;
    }

    /** P1.6 candidates' gate: eligible (texture, match) and not explained by the camera. */
    public static boolean[] foregroundEligible(int[] cur, int[] prev, int w, int h, MotionEstimator.Result me,
                                               GlobalMotion.Model cam, SegConfig c) {
        boolean[] e = eligible(cur, w, h, me, c);
        boolean[] x = cameraExplains(cur, prev, w, h, me, cam, c);
        for (int i = 0; i < e.length; i++) {
            e[i] &= !x[i];
        }
        return e;
    }

    /**
     * P1.6: the brief's rule (B7). Candidates are eligible blocks (see {@link #eligible}) with a
     * residual longer than
     * {@code resPx}. A region grows from a candidate to its 4-neighbours, but a neighbour joins only
     * if its residual agrees with the block it is reached from (close in length and direction). So a
     * patch of brick-alias vectors, large but pointing every which way, falls apart into tiny pieces
     * and is dropped, while a walker whose blocks all move the same way stays one region. Regions
     * that are too small or whose residuals spread too much are dropped. Ids 1, 2, ... in scan order.
     */
    public static Labels group(MotionField mv, boolean[] eligible, GlobalMotion.Model cam, SegConfig c) {
        float[][] r = residuals(mv, cam);
        int cols = mv.cols();
        int rows = mv.rows();
        int n = cols * rows;
        boolean[] cand = new boolean[n];
        double t2 = c.resPx() * c.resPx();
        for (int i = 0; i < n; i++) {
            cand[i] = eligible[i] && (double) r[0][i] * r[0][i] + (double) r[1][i] * r[1][i] > t2;
        }
        byte[] id = new byte[n];
        int minSize = Math.max(c.minRegionBlocks(), (int) Math.ceil(c.minRegionFrac() * n));
        grow(cand, r[0], r[1], cols, rows, c, minSize, id);
        return new Labels(cols, rows, id);
    }

    /** Region growing with the consistency rule; returns the number of regions kept. */
    static int grow(boolean[] cand, float[] rx, float[] ry, int cols, int rows, SegConfig c, int minSize, byte[] out) {
        int n = cand.length;
        boolean[] seen = new boolean[n];
        int[] queue = new int[n];
        double cosMax = Math.cos(Math.toRadians(c.consAngleDeg()));
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
                int[] nb = {cx > 0 ? i - 1 : -1, cx < cols - 1 ? i + 1 : -1, cy > 0 ? i - cols : -1, cy < rows - 1 ? i + cols : -1};
                for (int j : nb) {
                    if (j >= 0 && cand[j] && !seen[j] && consistent(rx, ry, i, j, c, cosMax)) {
                        seen[j] = true;
                        queue[tail++] = j;
                    }
                }
            }
            if (tail >= minSize && spread(rx, ry, queue, tail) <= c.maxSpreadPx()) {
                kept++;
                byte label = (byte) Math.min(kept, 255);
                for (int k = 0; k < tail; k++) {
                    out[queue[k]] = label;
                }
            }
        }
        return kept;
    }

    /** Close residuals, or two long residuals pointing the same way. */
    static boolean consistent(float[] rx, float[] ry, int i, int j, SegConfig c, double cosMax) {
        double dx = rx[i] - rx[j];
        double dy = ry[i] - ry[j];
        if (dx * dx + dy * dy <= c.consPx() * c.consPx()) {
            return true;
        }
        double mi = Math.hypot(rx[i], ry[i]);
        double mj = Math.hypot(rx[j], ry[j]);
        if (mi < c.consMinMag() || mj < c.consMinMag()) {
            return false;
        }
        return (rx[i] * rx[j] + ry[i] * ry[j]) / (mi * mj) >= cosMax;
    }

    /**
     * Robust spread of the members' residuals: the median distance from the component-wise median
     * residual. A walker region keeps a small spread even if a few limb blocks have wild vectors; a
     * patch whose vectors mostly disagree (brick aliases, leaves) has a large one.
     */
    static double spread(float[] rx, float[] ry, int[] members, int count) {
        float[] tx = new float[count];
        float[] ty = new float[count];
        for (int k = 0; k < count; k++) {
            tx[k] = rx[members[k]];
            ty[k] = ry[members[k]];
        }
        java.util.Arrays.sort(tx);
        java.util.Arrays.sort(ty);
        double mx = median(tx);
        double my = median(ty);
        float[] d = new float[count];
        for (int k = 0; k < count; k++) {
            d[k] = (float) Math.hypot(rx[members[k]] - mx, ry[members[k]] - my);
        }
        java.util.Arrays.sort(d);
        return median(d);
    }

    private static double median(float[] sorted) {
        int m = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[m] : (sorted[m - 1] + sorted[m]) / 2.0;
    }


    /**
     * P1.7 clean-up, all on the label grid (no new motion evidence):
     * <ol>
     * <li>a background block with at least 3 of its 4 neighbours in the same region joins it (flat
     *     patches on a jacket, blocks the camera test or the gate removed inside a person);</li>
     * <li>a background pocket that does not touch the frame border, is bordered by exactly one
     *     region and is no larger than that region is filled with it (holes inside a person);</li>
     * <li>optionally, every region grows by one block ({@code dilate}).</li>
     * </ol>
     * Returns new labels; the input is not changed. Deterministic (scan order, copies per pass).
     */
    public static Labels fill(Labels in, SegConfig c) {
        int cols = in.cols();
        int rows = in.rows();
        byte[] id = vote(in.id(), cols, rows);
        fillHoles(id, cols, rows);
        if (c.dilate()) {
            id = dilate(id, cols, rows);
        }
        return new Labels(cols, rows, id);
    }

    /** Step 1: 3-of-4 neighbour vote, computed on a copy so the result does not depend on scan order. */
    static byte[] vote(byte[] src, int cols, int rows) {
        byte[] out = src.clone();
        for (int i = 0; i < src.length; i++) {
            if (src[i] != 0) {
                continue;
            }
            int cx = i % cols;
            int cy = i / cols;
            byte[] nb = {cx > 0 ? src[i - 1] : 0, cx < cols - 1 ? src[i + 1] : 0,
                         cy > 0 ? src[i - cols] : 0, cy < rows - 1 ? src[i + cols] : 0};
            for (int a = 0; a < 4; a++) {
                if (nb[a] == 0) {
                    continue;
                }
                int same = 0;
                for (int b = 0; b < 4; b++) {
                    same += nb[b] == nb[a] ? 1 : 0;
                }
                if (same >= 3) {
                    out[i] = nb[a];
                    break;
                }
            }
        }
        return out;
    }

    /** Step 2: fill background pockets enclosed by one region and no larger than it. */
    static void fillHoles(byte[] id, int cols, int rows) {
        int n = id.length;
        int[] regionSize = new int[256];
        for (byte b : id) {
            regionSize[b & 0xFF]++;
        }
        boolean[] seen = new boolean[n];
        int[] queue = new int[n];
        for (int s = 0; s < n; s++) {
            if (id[s] != 0 || seen[s]) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = s;
            seen[s] = true;
            boolean border = false;
            int owner = -1;                      // -1 none yet, -2 several regions
            while (head < tail) {
                int i = queue[head++];
                int cx = i % cols;
                int cy = i / cols;
                border |= cx == 0 || cy == 0 || cx == cols - 1 || cy == rows - 1;
                int[] nb = {cx > 0 ? i - 1 : -1, cx < cols - 1 ? i + 1 : -1, cy > 0 ? i - cols : -1, cy < rows - 1 ? i + cols : -1};
                for (int j : nb) {
                    if (j < 0) {
                        continue;
                    }
                    if (id[j] == 0) {
                        if (!seen[j]) {
                            seen[j] = true;
                            queue[tail++] = j;
                        }
                    } else {
                        int r = id[j] & 0xFF;
                        owner = owner == -1 || owner == r ? r : -2;
                    }
                }
            }
            if (!border && owner > 0 && tail <= regionSize[owner]) {
                for (int k = 0; k < tail; k++) {
                    id[queue[k]] = (byte) owner;
                }
            }
        }
    }

    /** Step 3: one-block 4-neighbour dilation; where two regions compete, the lower id wins. */
    static byte[] dilate(byte[] src, int cols, int rows) {
        byte[] out = src.clone();
        for (int i = 0; i < src.length; i++) {
            if (src[i] != 0) {
                continue;
            }
            int cx = i % cols;
            int cy = i / cols;
            int best = 0;
            int[] nb = {cx > 0 ? i - 1 : -1, cx < cols - 1 ? i + 1 : -1, cy > 0 ? i - cols : -1, cy < rows - 1 ? i + cols : -1};
            for (int j : nb) {
                int r = j >= 0 ? src[j] & 0xFF : 0;
                if (r != 0 && (best == 0 || r < best)) {
                    best = r;
                }
            }
            out[i] = (byte) best;
        }
        return out;
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
