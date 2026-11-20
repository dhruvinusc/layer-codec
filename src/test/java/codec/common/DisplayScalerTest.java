package codec.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import codec.common.Types.Frame;
import org.junit.jupiter.api.Test;

/** The display scaler is pure math, so it is tested without a screen. */
class DisplayScalerTest {

    private static int red(int packed) {
        return (packed >> 16) & 0xFF;
    }

    @Test
    void sameSizeIsExact() {
        Frame f = Frame.allocate(3, 2);
        for (int i = 0; i < 6; i++) {
            f.r()[i] = 40 * i;
            f.g()[i] = 255 - 40 * i;
            f.b()[i] = 200;
        }
        int[] out = new int[6];
        new Display.Scaler(3, 2, 3, 2).apply(f, out);
        for (int i = 0; i < 6; i++) {
            assertEquals((f.r()[i] << 16) | (f.g()[i] << 8) | 200, out[i]);
        }
    }

    @Test
    void flatColorStaysFlatWhenScaled() {
        Frame f = Frame.allocate(1920, 1080);
        java.util.Arrays.fill(f.r(), 255);
        java.util.Arrays.fill(f.g(), 128);
        java.util.Arrays.fill(f.b(), 7);
        int[] out = new int[1382 * 778];
        new Display.Scaler(1920, 1080, 1382, 778).apply(f, out);
        for (int v : out) {
            assertEquals((255 << 16) | (128 << 8) | 7, v);
        }
    }

    @Test
    void halvingAveragesNeighbours() {
        // Columns 0,100,200,250: halving to 2 samples the midpoints, giving the pair means.
        Frame f = Frame.allocate(4, 1);
        int[] r = {0, 100, 200, 250};
        System.arraycopy(r, 0, f.r(), 0, 4);
        int[] out = new int[2];
        new Display.Scaler(4, 1, 2, 1).apply(f, out);
        assertEquals(50, red(out[0]));
        assertEquals(225, red(out[1]));
    }

    @Test
    void packedInputScalesBitIdenticalToPlanes() {
        java.util.Random rnd = new java.util.Random(13);
        Frame f = Frame.allocate(64, 36);
        int[] packed = new int[64 * 36];
        for (int i = 0; i < packed.length; i++) {
            f.r()[i] = rnd.nextInt(256);
            f.g()[i] = rnd.nextInt(256);
            f.b()[i] = rnd.nextInt(256);
            packed[i] = (f.r()[i] << 16) | (f.g()[i] << 8) | f.b()[i];
        }
        int[][] sizes = {{64, 36}, {46, 26}, {33, 19}};
        for (int[] d : sizes) {
            Display.Scaler s = new Display.Scaler(64, 36, d[0], d[1]);
            int[] a = new int[d[0] * d[1]];
            int[] b = new int[d[0] * d[1]];
            s.apply(f, a);
            s.applyPacked(packed, b);
            org.junit.jupiter.api.Assertions.assertArrayEquals(a, b, "size " + d[0] + "x" + d[1]);
        }
    }
}
