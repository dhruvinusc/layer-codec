package codec.encoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codec.common.Types.MotionField;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SegmenterReliabilityTest {

    private static MotionEstimator.Result result(int n, int sad) {
        int[] s = new int[n];
        java.util.Arrays.fill(s, sad);
        return new MotionEstimator.Result(new MotionField(n, 1, new short[n], new short[n]), s, s.clone());
    }

    @Test
    void varianceOfFlatAndTwoLevelBlocks() {
        int[] flat = new int[256];
        java.util.Arrays.fill(flat, 77);
        assertEquals(0, Segmenter.blockVariance(flat, 16, 0, 0));
        int[] halves = new int[256];
        for (int i = 0; i < 256; i++) {
            halves[i] = i < 128 ? 0 : 20;
        }
        assertEquals(100, Segmenter.blockVariance(halves, 16, 0, 0));
    }

    @Test
    void flatBlockIsUnreliableTexturedGoodMatchIsReliable() {
        int[] y = new int[32 * 16];
        Random rnd = new Random(3);
        for (int r = 0; r < 16; r++) {
            for (int x = 16; x < 32; x++) {
                y[r * 32 + x] = rnd.nextInt(256);
            }
        }
        boolean[] ok = Segmenter.reliable(y, 32, 16, result(2, 0), Segmenter.SegConfig.DEFAULT);
        assertFalse(ok[0], "flat block");
        assertTrue(ok[1], "textured block with SAD 0");
    }

    @Test
    void badMatchIsUnreliable() {
        int[] y = new int[16 * 16];
        Random rnd = new Random(4);
        for (int i = 0; i < y.length; i++) {
            y[i] = rnd.nextInt(256);
        }
        int limit = Segmenter.SegConfig.DEFAULT.maxMatchMad() * 256;
        assertTrue(Segmenter.reliable(y, 16, 16, result(1, limit), Segmenter.SegConfig.DEFAULT)[0]);
        assertFalse(Segmenter.reliable(y, 16, 16, result(1, limit + 1), Segmenter.SegConfig.DEFAULT)[0]);
    }
}
