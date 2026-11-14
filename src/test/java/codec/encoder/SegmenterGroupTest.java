package codec.encoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codec.common.Types.Labels;
import codec.common.Types.MotionField;
import codec.encoder.GlobalMotion.Kind;
import codec.encoder.GlobalMotion.Model;
import codec.encoder.Segmenter.SegConfig;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SegmenterGroupTest {

    static final int COLS = 40;
    static final int ROWS = 20;
    /** Camera pans by (6, 0) in the D12 convention. */
    static final Model CAM = new Model(Kind.TRANSLATION, GlobalMotion.translation(6, 0), 0, 0, 0);

    private static short[][] background() {
        short[] dx = new short[COLS * ROWS];
        short[] dy = new short[COLS * ROWS];
        Arrays.fill(dx, (short) 6);
        return new short[][] {dx, dy};
    }

    private static void patch(short[][] v, int x0, int y0, int w, int h, int dx, int dy) {
        for (int y = y0; y < y0 + h; y++) {
            for (int x = x0; x < x0 + w; x++) {
                v[0][y * COLS + x] = (short) dx;
                v[1][y * COLS + x] = (short) dy;
            }
        }
    }

    private static boolean[] allReliable() {
        boolean[] r = new boolean[COLS * ROWS];
        Arrays.fill(r, true);
        return r;
    }

    private static Labels run(short[][] v, boolean[] rel) {
        return Segmenter.group(new MotionField(COLS, ROWS, v[0], v[1]), rel, CAM, SegConfig.DEFAULT);
    }

    @Test
    void coherentMoverIsOneRegionAndBackgroundStaysZero() {
        short[][] v = background();
        patch(v, 5, 5, 3, 6, -2, 0);            // walker: residual (-8, 0)
        Labels l = run(v, allReliable());
        assertEquals(1, l.get(5, 5));
        assertEquals(1, l.get(7, 10));
        assertEquals(0, l.get(0, 0));
        assertEquals(0, l.get(8, 5));
    }

    @Test
    void touchingPatchesMovingOppositeWaysBecomeTwoRegions() {
        short[][] v = background();
        patch(v, 10, 5, 3, 6, -2, 0);           // residual (-8, 0)
        patch(v, 13, 5, 3, 6, 14, 0);           // residual (+8, 0), touches the first
        Labels l = run(v, allReliable());
        assertNotEquals(0, l.get(10, 5));
        assertNotEquals(0, l.get(13, 5));
        assertNotEquals(l.get(10, 5), l.get(13, 5), "opposite directions must not merge");
    }

    @Test
    void randomDirectionAliasPatchIsDropped() {
        // Brick-wall case: large residuals, random directions, one big contiguous patch.
        short[][] v = background();
        Random rnd = new Random(7);
        for (int y = 2; y < 14; y++) {
            for (int x = 20; x < 36; x++) {
                double a = rnd.nextDouble() * 2 * Math.PI;
                v[0][y * COLS + x] = (short) Math.round(6 + 8 * Math.cos(a));
                v[1][y * COLS + x] = (short) Math.round(8 * Math.sin(a));
            }
        }
        Labels l = run(v, allReliable());
        int fg = 0;
        for (byte b : l.id()) {
            fg += b != 0 ? 1 : 0;
        }
        assertTrue(fg < 0.15 * 12 * 16, "alias patch should mostly vanish, kept " + fg);
    }

    @Test
    void fastMoverWithVaryingSpeedJoinsByDirection() {
        // Residual length 5 to 9 px along +x: neighbours differ by up to 2 px or more, same direction.
        short[][] v = background();
        for (int y = 4; y < 10; y++) {
            for (int x = 4; x < 8; x++) {
                v[0][y * COLS + x] = (short) (6 + 5 + (x - 4) + (y % 2) * 0);
            }
        }
        Labels l = run(v, allReliable());
        assertEquals(1, l.get(4, 4));
        assertEquals(1, l.get(7, 9));
    }

    @Test
    void smallAndUnreliableBlocksAreNotForeground() {
        short[][] v = background();
        patch(v, 2, 2, 1, 3, -2, 0);            // 3 blocks: below the minimum of 4
        patch(v, 20, 10, 3, 3, -2, 0);          // 9 blocks, but unreliable
        boolean[] rel = allReliable();
        for (int y = 10; y < 13; y++) {
            for (int x = 20; x < 23; x++) {
                rel[y * COLS + x] = false;
            }
        }
        Labels l = run(v, rel);
        assertEquals(0, l.get(2, 2));
        assertEquals(0, l.get(21, 11));
    }

    @Test
    void residualBelowThresholdIsBackground() {
        short[][] v = background();
        patch(v, 5, 5, 4, 4, 8, 0);             // residual (2, 0) < 2.5 px
        assertEquals(0, run(v, allReliable()).get(6, 6));
    }

    @Test
    void consistencyRule() {
        float[] rx = {5, 6, 9, -5, 1};
        float[] ry = {0, 1, 1, 0, 0};
        double cos30 = Math.cos(Math.toRadians(30));
        SegConfig c = SegConfig.DEFAULT;
        assertTrue(Segmenter.consistent(rx, ry, 0, 1, c, cos30));          // 1.4 px apart
        assertTrue(Segmenter.consistent(rx, ry, 0, 2, c, cos30));          // 4 px apart, same direction, both long
        assertTrue(!Segmenter.consistent(rx, ry, 0, 3, c, cos30));         // opposite
        assertTrue(!Segmenter.consistent(rx, ry, 2, 4, c, cos30));         // 8 px apart and one is short
    }
}
