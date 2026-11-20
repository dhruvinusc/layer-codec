package codec.encoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codec.common.Types.MotionField;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MotionEstimatorTest {

    private static final int W = 128;
    private static final int H = 96;

    /** Uniform noise: every offset looks different, so the true one is the only SAD = 0. */
    private static int[] noise(int w, int h, long seed) {
        Random rnd = new Random(seed);
        int[] p = new int[w * h];
        for (int i = 0; i < p.length; i++) {
            p[i] = rnd.nextInt(256);
        }
        return p;
    }

    /** cur(x, y) = prev(x + dx, y + dy), edges replicated. Matches the D12 convention exactly. */
    private static int[] shifted(int[] prev, int w, int h, int dx, int dy) {
        int[] cur = new int[w * h];
        for (int y = 0; y < h; y++) {
            int sy = Math.min(h - 1, Math.max(0, y + dy));
            for (int x = 0; x < w; x++) {
                int sx = Math.min(w - 1, Math.max(0, x + dx));
                cur[y * w + x] = prev[sy * w + sx];
            }
        }
        return cur;
    }

    private static void assertGlobalShiftFound(int dx, int dy, int range) {
        int[] prev = noise(W, H, 576);
        int[] cur = shifted(prev, W, H, dx, dy);
        MotionEstimator.Result r = MotionEstimator.fullSearch(cur, prev, W, H, range);
        MotionField f = r.field();
        int checked = 0;
        for (int by = 0; by < f.rows(); by++) {
            for (int bx = 0; bx < f.cols(); bx++) {
                int x0 = bx * 16;
                int y0 = by * 16;
                // Only blocks whose source lies fully inside the frame are an exact copy.
                boolean interior = x0 + dx >= 0 && x0 + dx + 16 <= W && y0 + dy >= 0 && y0 + dy + 16 <= H;
                if (!interior) {
                    continue;
                }
                int i = by * f.cols() + bx;
                assertEquals(dx, f.dx()[i], "dx at block " + bx + "," + by);
                assertEquals(dy, f.dy()[i], "dy at block " + bx + "," + by);
                assertEquals(0, r.sadBest()[i]);
                checked++;
            }
        }
        assertTrue(checked > 0, "no interior blocks checked");
    }

    @Test
    void recoversKnownShiftsIncludingNegative() {
        assertGlobalShiftFound(-7, 2, 16);
        assertGlobalShiftFound(3, -5, 16);
        assertGlobalShiftFound(-11, -13, 16);
        assertGlobalShiftFound(0, 0, 16);
    }

    @Test
    void recoversShiftsAtTheRangeEdge() {
        assertGlobalShiftFound(16, -16, 16);
        assertGlobalShiftFound(-16, 16, 16);
        assertGlobalShiftFound(8, 0, 8);
    }

    @Test
    void neverPointsOutsideThePaddedFrameOrBeyondRange() {
        int[] prev = noise(W, H, 1);
        int[] cur = shifted(prev, W, H, 20, -20); // beyond range 16: cannot be found exactly
        MotionEstimator.Result r = MotionEstimator.fullSearch(cur, prev, W, H, 16);
        MotionField f = r.field();
        for (int by = 0; by < f.rows(); by++) {
            for (int bx = 0; bx < f.cols(); bx++) {
                int i = by * f.cols() + bx;
                int x = bx * 16 + f.dx()[i];
                int y = by * 16 + f.dy()[i];
                assertTrue(Math.abs(f.dx()[i]) <= 16 && Math.abs(f.dy()[i]) <= 16);
                assertTrue(x >= 0 && x + 16 <= W && y >= 0 && y + 16 <= H, "block " + i + " leaves frame");
            }
        }
    }

    @Test
    void flatFrameGivesZeroVectorsByTieBreak() {
        int[] flat = new int[W * H];
        java.util.Arrays.fill(flat, 128);
        MotionEstimator.Result r = MotionEstimator.fullSearch(flat, flat.clone(), W, H, 16);
        for (int i = 0; i < r.sadBest().length; i++) {
            assertEquals(0, r.field().dx()[i]);
            assertEquals(0, r.field().dy()[i]);
            assertEquals(0, r.sadBest()[i]);
            assertEquals(0, r.sadZero()[i]);
        }
    }

    @Test
    void sadZeroAndMadAreReportedCorrectly() {
        int[] prev = new int[W * H];
        int[] cur = new int[W * H];
        java.util.Arrays.fill(prev, 100);
        java.util.Arrays.fill(cur, 103); // every pixel off by 3, no offset can do better
        MotionEstimator.Result r = MotionEstimator.fullSearch(cur, prev, W, H, 4);
        assertEquals(3 * 256, r.sadZero()[0]);
        assertEquals(3 * 256, r.sadBest()[0]);
        assertEquals(3.0, r.mad(0), 1e-12);
        assertEquals(0, r.field().dx()[0]);
    }

    /** Textbook oracle: full SAD at every in-frame offset, no early exit, same tie-break rule. */
    private static int[] oracle(int[] cur, int[] prev, int w, int h, int range, int bx, int by) {
        int x0 = bx * 16;
        int y0 = by * 16;
        int best = blockSad(cur, prev, w, x0, y0, x0, y0);
        int bestNorm = 0;
        int bdx = 0;
        int bdy = 0;
        for (int vy = -range; vy <= range; vy++) {
            for (int vx = -range; vx <= range; vx++) {
                int px = x0 + vx;
                int py = y0 + vy;
                if (px < 0 || py < 0 || px + 16 > w || py + 16 > h || (vx == 0 && vy == 0)) {
                    continue;
                }
                int s = blockSad(cur, prev, w, x0, y0, px, py);
                int norm = Math.abs(vx) + Math.abs(vy);
                if (s < best || (s == best && norm < bestNorm)) {
                    best = s;
                    bestNorm = norm;
                    bdx = vx;
                    bdy = vy;
                }
            }
        }
        return new int[] {bdx, bdy, best};
    }

    private static int blockSad(int[] a, int[] b, int w, int ax, int ay, int bx, int by) {
        int s = 0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                s += Math.abs(a[(ay + y) * w + ax + x] - b[(by + y) * w + bx + x]);
            }
        }
        return s;
    }

    @Test
    void matchesTextbookOracleOnMixedContent() {
        int w = 96;
        int h = 64;
        Random rnd = new Random(42);
        for (int trial = 0; trial < 6; trial++) {
            // Mixed content: low-contrast noise, flat patches and stripes create many near-ties.
            int[] prev = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int v = (x / 24 + y / 16) % 3 == 0 ? 90 : ((x % 4 < 2) ? 60 : 180);
                    prev[y * w + x] = Math.min(255, v + rnd.nextInt(6));
                }
            }
            int[] cur = shifted(prev, w, h, rnd.nextInt(13) - 6, rnd.nextInt(13) - 6);
            for (int k = 0; k < 200; k++) {
                cur[rnd.nextInt(w * h)] = rnd.nextInt(256); // noise so not every block is exact
            }
            int range = 3 + trial;
            MotionEstimator.Result r = MotionEstimator.fullSearch(cur, prev, w, h, range);
            for (int by = 0; by < h / 16; by++) {
                for (int bx = 0; bx < w / 16; bx++) {
                    int i = by * (w / 16) + bx;
                    int[] o = oracle(cur, prev, w, h, range, bx, by);
                    String at = "trial " + trial + " block " + bx + "," + by;
                    assertEquals(o[0], r.field().dx()[i], at);
                    assertEquals(o[1], r.field().dy()[i], at);
                    assertEquals(o[2], r.sadBest()[i], at);
                }
            }
        }
    }

    @Test
    void rejectsUnpaddedOrMismatchedInput() {
        assertThrows(IllegalArgumentException.class,
                () -> MotionEstimator.fullSearch(new int[100 * 32], new int[100 * 32], 100, 32, 16));
        assertThrows(IllegalArgumentException.class,
                () -> MotionEstimator.fullSearch(new int[32 * 32], new int[16 * 32], 32, 32, 16));
        assertThrows(IllegalArgumentException.class,
                () -> MotionEstimator.fullSearch(new int[32 * 32], new int[32 * 32], 32, 32, -1));
    }
}
