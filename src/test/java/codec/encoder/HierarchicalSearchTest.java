package codec.encoder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codec.encoder.MotionEstimator.HierParams;
import codec.encoder.MotionEstimator.Result;
import java.util.Random;
import org.junit.jupiter.api.Test;

class HierarchicalSearchTest {

    private static final int W = 256;
    private static final int H = 192;

    /** Smooth-ish random texture (noise blurred 3x3) so the pyramid keeps structure. */
    private static int[] texture(long seed) {
        Random rnd = new Random(seed);
        int[] n = new int[W * H];
        for (int i = 0; i < n.length; i++) {
            n[i] = rnd.nextInt(256);
        }
        int[] t = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int s = 0;
                for (int oy = -1; oy <= 1; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        s += n[Math.min(H - 1, Math.max(0, y + oy)) * W + Math.min(W - 1, Math.max(0, x + ox))];
                    }
                }
                t[y * W + x] = s / 9;
            }
        }
        return t;
    }

    private static int[] shifted(int[] prev, int dx, int dy) {
        int[] cur = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int sx = Math.min(W - 1, Math.max(0, x + dx));
                int sy = Math.min(H - 1, Math.max(0, y + dy));
                cur[y * W + x] = prev[sy * W + sx];
            }
        }
        return cur;
    }

    private static void assertInteriorExact(int dx, int dy) {
        int[] prev = texture(576);
        int[] cur = shifted(prev, dx, dy);
        Result r = MotionEstimator.hierarchical(cur, prev, W, H, HierParams.DEFAULT, null, 0, 0);
        int cols = W / 16;
        int rows = H / 16;
        int checked = 0;
        for (int by = 0; by < rows; by++) {
            for (int bx = 0; bx < cols; bx++) {
                int x0 = bx * 16 + dx;
                int y0 = by * 16 + dy;
                boolean interior = x0 >= 0 && y0 >= 0 && x0 + 16 <= W && y0 + 16 <= H;
                if (interior) {
                    int i = by * cols + bx;
                    assertEquals(dx, r.field().dx()[i], "dx at block " + bx + "," + by);
                    assertEquals(dy, r.field().dy()[i], "dy at block " + bx + "," + by);
                    assertEquals(0, r.sadBest()[i]);
                    checked++;
                }
            }
        }
        assertTrue(checked > 50);
    }

    @Test
    void recovers30PixelShiftThatFullSearch16Cannot() {
        assertInteriorExact(-30, 12);
    }

    @Test
    void recoversSmallAndZeroShifts() {
        assertInteriorExact(5, -3);
        assertInteriorExact(0, 0);
        assertInteriorExact(-1, 1);
    }

    @Test
    void halfIsRounded2x2Average() {
        int[] src = {0, 1, 10, 20, 3, 4, 30, 40};
        assertArrayEquals(new int[] {2, 25}, MotionEstimator.half(src, 4, 2));
    }

    @Test
    void median3() {
        assertEquals(2, MotionEstimator.median3(1, 2, 3));
        assertEquals(2, MotionEstimator.median3(3, 1, 2));
        assertEquals(-4, MotionEstimator.median3(-4, -4, 9));
    }

    @Test
    void deterministic() {
        int[] prev = texture(1);
        int[] cur = shifted(prev, 7, -2);
        Result a = MotionEstimator.hierarchical(cur, prev, W, H, HierParams.DEFAULT, null, 0, 0);
        Result b = MotionEstimator.hierarchical(cur, prev, W, H, HierParams.DEFAULT, null, 0, 0);
        assertArrayEquals(a.field().dx(), b.field().dx());
        assertArrayEquals(a.field().dy(), b.field().dy());
    }
}
