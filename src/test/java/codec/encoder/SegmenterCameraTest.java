package codec.encoder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codec.encoder.GlobalMotion.Kind;
import codec.encoder.GlobalMotion.Model;
import codec.encoder.Segmenter.SegConfig;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SegmenterCameraTest {

    static final int W = 128;
    static final int H = 64;

    /** prev = random texture; cur = prev shifted so content moved by (-sx, -sy), i.e. D12 vector (sx, sy). */
    private static int[][] pair(int sx, int sy, long seed) {
        int[] prev = new int[W * H];
        Random r = new Random(seed);
        for (int i = 0; i < prev.length; i++) {
            prev[i] = r.nextInt(256);
        }
        int[] cur = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int px = Math.min(W - 1, Math.max(0, x + sx));
                int py = Math.min(H - 1, Math.max(0, y + sy));
                cur[y * W + x] = prev[py * W + px];
            }
        }
        return new int[][] {cur, prev};
    }

    @Test
    void blockThatMovesWithTheCameraIsExplainedEvenIfItsOwnVectorIsWrong() {
        int[][] f = pair(4, 0, 1);
        MotionEstimator.Result me = MotionEstimator.fullSearch(f[0], f[1], W, H, 8);
        // Pretend the search had picked a wrong alias: the camera test must still call it background.
        me.field().dx()[9] = -6;
        Model cam = new Model(Kind.TRANSLATION, GlobalMotion.translation(4, 0), 0, 0, 0);
        boolean[] x = Segmenter.cameraExplains(f[0], f[1], W, H, me, cam, SegConfig.DEFAULT);
        assertTrue(x[9]);
    }

    @Test
    void independentMoverIsNotExplained() {
        int[][] f = pair(4, 0, 2);
        // Paste a block in cur that came from 10 px further right in prev: independent motion (14, 0).
        int x0 = 32;
        int y0 = 16;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                f[0][(y0 + y) * W + x0 + x] = f[1][(y0 + y) * W + x0 + x + 14];
            }
        }
        MotionEstimator.Result me = MotionEstimator.fullSearch(f[0], f[1], W, H, 16);
        Model cam = new Model(Kind.TRANSLATION, GlobalMotion.translation(4, 0), 0, 0, 0);
        boolean[] x = Segmenter.cameraExplains(f[0], f[1], W, H, me, cam, SegConfig.DEFAULT);
        int i = (y0 / 16) * (W / 16) + x0 / 16;
        assertFalse(x[i], "a block that moved 10 px against the camera must stay a candidate");
        assertTrue(x[0]);
    }

    @Test
    void periodicTextureAliasesAreRemovedByTheFullGate() {
        // Vertical stripes with an 8 px period, camera pan 3 px: vectors 3 and 3 - 8 = -5 match equally.
        int[] prev = new int[W * H];
        for (int i = 0; i < prev.length; i++) {
            prev[i] = (i % W) % 8 < 4 ? 40 : 200;
        }
        int[] cur = new int[W * H];
        for (int i = 0; i < cur.length; i++) {
            int x = i % W;
            cur[i] = prev[i - x + Math.min(W - 1, x + 3)];
        }
        MotionEstimator.Result me = MotionEstimator.fullSearch(cur, prev, W, H, 8);
        for (int i = 0; i < me.field().dx().length; i++) {
            me.field().dx()[i] = -5;                 // a whole patch of consistent aliases
        }
        Model cam = new Model(Kind.TRANSLATION, GlobalMotion.translation(3, 0), 0, 0, 0);
        boolean[] gate = Segmenter.foregroundEligible(cur, prev, W, H, me, cam, SegConfig.DEFAULT);
        for (int i = 0; i < gate.length - W / 16; i++) {   // last row may touch the padded edge
            assertFalse(gate[i], "block " + i + " should be explained by the camera");
        }
    }
}
