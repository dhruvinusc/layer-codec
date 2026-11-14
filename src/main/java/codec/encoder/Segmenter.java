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
        public static final SegConfig DEFAULT = new SegConfig(100, 12, 20, 2.0, 0.15, 1, 2.5, 2.0, 4.0, 30.0, 4, 0.002, 3.0, false);
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
     * P1.8 temporal vote with one frame of lookahead. A block is foreground in frame t if it is
     * foreground in at least 2 of t-1, t, t+1. Frames are not aligned (the camera moves up to two
     * blocks per frame on video2), so the neighbours are read through motion: frame t-1 at the
     * position block i came from (its own vector), frame t+1 at the position that frame's vector at i
     * points back from (an approximation of the inverse). A missing neighbour (first or last frame)
     * counts as a copy of frame t, so the clip ends keep their per-frame labels.
     */
    public static final class Temporal {
        private Labels before;        // frame t-2 (raw), or null
        private Labels middle;        // frame t-1 (raw), or null
        private MotionField middleMv; // vectors of frame t-1 (towards t-2)

        /** Feeds frame t's raw labels and vectors; returns the final labels of frame t-1 (null at first). */
        public Labels push(Labels raw, MotionField mv) {
            Labels out = middle == null ? null : vote(before, middle, middleMv, raw, mv);
            before = middle;
            middle = raw;
            middleMv = mv;
            return out;
        }

        /** Final labels of the last frame pushed (null if nothing was pushed). */
        public Labels flush() {
            return middle == null ? null : vote(before, middle, middleMv, null, null);
        }

        static Labels vote(Labels prev, Labels cur, MotionField curMv, Labels next, MotionField nextMv) {
            int cols = cur.cols();
            int rows = cur.rows();
            byte[] out = new byte[cols * rows];
            for (int i = 0; i < out.length; i++) {
                int cx = i % cols;
                int cy = i / cols;
                byte c = cur.id()[i];
                byte p = prev == null ? c : prev.id()[shifted(cx, cy, curMv.dx()[i], curMv.dy()[i], cols, rows)];
                byte n = next == null ? c : next.id()[shifted(cx, cy, -nextMv.dx()[i], -nextMv.dy()[i], cols, rows)];
                int votes = (c != 0 ? 1 : 0) + (p != 0 ? 1 : 0) + (n != 0 ? 1 : 0);
                out[i] = votes >= 2 ? (c != 0 ? c : p != 0 ? p : n) : 0;
            }
            return new Labels(cols, rows, out);
        }

        /** Block index containing the center of block (cx, cy) moved by (dx, dy), clamped to the grid. */
        private static int shifted(int cx, int cy, int dx, int dy, int cols, int rows) {
            int x = Math.floorDiv(cx * Types.MB + Types.MB / 2 + dx, Types.MB);
            int y = Math.floorDiv(cy * Types.MB + Types.MB / 2 + dy, Types.MB);
            return Math.min(rows - 1, Math.max(0, y)) * cols + Math.min(cols - 1, Math.max(0, x));
        }
    }

    /**
     * Per-frame facts for the stats CSV (frame 0 copies frame 1, 03 D4).
     *
     * @param frame        frame index
     * @param camera       accepted camera model
     * @param inlierRatio  share of reliable blocks the camera model explains
     * @param reliablePct  share of blocks trusted for the camera fit
     * @param rawFgPct     foreground share before the temporal vote
     * @param fgPct        final foreground share
     * @param regions      distinct foreground ids in the final labels
     * @param meMs         motion search time
     * @param segMs        camera fit + labelling + vote time
     */
    public record FrameStats(int frame, GlobalMotion.Kind camera, double inlierRatio, double reliablePct,
                             double rawFgPct, double fgPct, int regions, double meMs, double segMs) {
    }

    /**
     * The whole P1 pipeline for one clip, streamed frame by frame (never holds the clip): motion
     * search seeded with the previous field and camera vector, reliability, camera fit, candidate
     * gate, grouping, fill, temporal vote. Feed padded Y planes in order; final labels come out one
     * frame behind (two at the start: frame 0 copies frame 1).
     */
    public static final class Run {
        private final int w;
        private final int h;
        private final SegConfig sc;
        private final GlobalMotion.GmConfig gc;
        private final MotionEstimator.HierParams hp;
        private final Temporal temporal = new Temporal();
        private final java.util.List<FrameStats> stats = new java.util.ArrayList<>();
        private final java.util.ArrayDeque<double[]> pending = new java.util.ArrayDeque<>();
        private int[] prevY;
        private MotionField prevField;
        private int[] hint = {0, 0};
        private int t = -1;

        public Run(int w, int h, SegConfig sc, GlobalMotion.GmConfig gc, MotionEstimator.HierParams hp) {
            this.w = w;
            this.h = h;
            this.sc = sc;
            this.gc = gc;
            this.hp = hp;
        }

        /** Feeds the next frame's padded Y plane; returns final labels now ready, in frame order. */
        public java.util.List<Labels> push(int[] y) {
            t++;
            java.util.List<Labels> ready = new java.util.ArrayList<>();
            if (prevY != null) {
                long t0 = System.nanoTime();
                MotionEstimator.Result me = MotionEstimator.hierarchical(y, prevY, w, h, hp, prevField, hint[0], hint[1]);
                long t1 = System.nanoTime();
                boolean[] rel = reliable(y, w, h, me, sc);
                GlobalMotion.Model cam = GlobalMotion.fit(me.field(), rel, w, h, gc);
                Labels raw = fill(group(me.field(), foregroundEligible(y, prevY, w, h, me, cam, sc), cam, sc), sc);
                Labels fin = temporal.push(raw, me.field());
                long t2 = System.nanoTime();
                pending.add(new double[] {t, cam.kind().ordinal(), cam.inlierRatio(), 100.0 * count(rel) / rel.length,
                        pct(raw), (t1 - t0) / 1e6, (t2 - t1) / 1e6});
                emit(fin, ready);
                prevField = me.field();
                hint = cam.centerVector(w, h);
            }
            prevY = y;
            return ready;
        }

        /** Labels of the last frame (and of frame 0 for a 2-frame clip). Call once at the end. */
        public java.util.List<Labels> finish() {
            java.util.List<Labels> ready = new java.util.ArrayList<>();
            emit(temporal.flush(), ready);
            return ready;
        }

        public java.util.List<FrameStats> stats() {
            return stats;
        }

        private void emit(Labels fin, java.util.List<Labels> ready) {
            if (fin == null) {
                return;
            }
            double[] p = pending.poll();
            FrameStats fs = new FrameStats((int) p[0], GlobalMotion.Kind.values()[(int) p[1]], p[2], p[3], p[4],
                    pct(fin), regions(fin), p[5], p[6]);
            if (fs.frame() == 1) {
                ready.add(fin);   // frame 0 reuses frame 1's labels (03 D4)
                stats.add(new FrameStats(0, fs.camera(), fs.inlierRatio(), fs.reliablePct(), fs.rawFgPct(), fs.fgPct(), fs.regions(), 0, 0));
            }
            ready.add(fin);
            stats.add(fs);
        }
    }

    static int count(boolean[] b) {
        int n = 0;
        for (boolean v : b) {
            n += v ? 1 : 0;
        }
        return n;
    }

    static double pct(Labels l) {
        int n = 0;
        for (byte b : l.id()) {
            n += b != 0 ? 1 : 0;
        }
        return 100.0 * n / l.id().length;
    }

    static int regions(Labels l) {
        boolean[] seen = new boolean[256];
        int k = 0;
        for (byte b : l.id()) {
            int v = b & 0xFF;
            if (v != 0 && !seen[v]) {
                seen[v] = true;
                k++;
            }
        }
        return k;
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
