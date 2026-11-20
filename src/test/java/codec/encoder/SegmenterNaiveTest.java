package codec.encoder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import codec.common.Types;
import codec.common.Types.Labels;
import codec.common.Types.MotionField;
import codec.encoder.GlobalMotion.Kind;
import codec.encoder.GlobalMotion.Model;
import codec.encoder.Segmenter.SegConfig;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SegmenterNaiveTest {

    @Test
    void componentsKeepOnlyGroupsOfMinSizeInScanOrder() {
        // 6x4 grid: a 2x2 group (top left), a single block, and a 1x4 bar (right column).
        int cols = 6;
        int rows = 4;
        boolean[] c = new boolean[cols * rows];
        int[] on = {0, 1, 6, 7, 3, 5, 11, 17, 23};
        for (int i : on) {
            c[i] = true;
        }
        byte[] out = new byte[c.length];
        int kept = Segmenter.components(c, cols, rows, 4, out);
        assertEquals(2, kept);
        assertEquals(1, out[0]);
        assertEquals(1, out[7]);
        assertEquals(0, out[3], "single block dropped");
        assertEquals(2, out[5]);
        assertEquals(2, out[23]);
    }

    @Test
    void diagonalNeighboursAreNotConnected() {
        boolean[] c = {true, false, false, true};
        byte[] out = new byte[4];
        assertEquals(0, Segmenter.components(c, 2, 2, 2, out));
    }

    @Test
    void residualIsVectorMinusCamera() {
        MotionField f = new MotionField(2, 1, new short[] {7, 2}, new short[] {0, 1});
        Model cam = new Model(Kind.TRANSLATION, GlobalMotion.translation(7, 0), 1, 2, 0);
        float[][] r = Segmenter.residuals(f, cam);
        assertEquals(0f, r[0][0]);
        assertEquals(0f, r[1][0]);
        assertEquals(-5f, r[0][1]);
        assertEquals(1f, r[1][1]);
    }

    @Test
    void naiveFindsTexturedMoverAndIgnoresFlatBlocks() {
        int w = 128;
        int h = 64;
        int cols = w / Types.MB;
        int rows = h / Types.MB;
        int[] y = new int[w * h];
        Random rnd = new Random(5);
        for (int i = 0; i < y.length; i++) {
            int x = i % w;
            y[i] = x < 64 ? rnd.nextInt(256) : 128;   // left half textured, right half flat
        }
        short[] dx = new short[cols * rows];
        short[] dy = new short[cols * rows];
        // Camera pans by 4; a 2x2 mover at (1..2, 1..2) and a 2x2 flat "mover" at (5..6, 1..2).
        java.util.Arrays.fill(dx, (short) 4);
        int[] movers = {9, 10, 17, 18, 13, 14, 21, 22};
        for (int i : movers) {
            dx[i] = -6;
        }
        Model cam = new Model(Kind.TRANSLATION, GlobalMotion.translation(4, 0), 0, 0, 0);
        Labels l = Segmenter.naive(y, w, h, new MotionField(cols, rows, dx, dy), cam, SegConfig.DEFAULT);
        assertEquals(1, l.get(1, 1));
        assertEquals(1, l.get(2, 2));
        assertEquals(0, l.get(5, 1), "flat blocks are never candidates");
        assertEquals(0, l.get(0, 0));
    }
}
