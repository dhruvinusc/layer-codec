package codec.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class DctTest {

    private static final double EPS = 1e-9;

    /** Textbook oracle: the class formula as a direct quadruple sum, no tables, no tricks. */
    private static double[] oracleForward(int[] block) {
        double[] f = new double[64];
        for (int v = 0; v < 8; v++) {
            for (int u = 0; u < 8; u++) {
                double cu = u == 0 ? 1 / Math.sqrt(2) : 1;
                double cv = v == 0 ? 1 / Math.sqrt(2) : 1;
                double s = 0;
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        s += (block[y * 8 + x] - 128)
                                * Math.cos((2 * x + 1) * u * Math.PI / 16)
                                * Math.cos((2 * y + 1) * v * Math.PI / 16);
                    }
                }
                f[v * 8 + u] = 0.25 * cu * cv * s;
            }
        }
        return f;
    }

    private static double[] fwd(int[] block) {
        double[] out = new double[64];
        Dct.forward(block, 8, 0, 0, out, new double[64]);
        return out;
    }

    private static int[] randomBlock(Random rnd) {
        int[] b = new int[64];
        for (int i = 0; i < 64; i++) {
            b[i] = rnd.nextInt(256);
        }
        return b;
    }

    @Test
    void matchesTextbookOracleOnRandomBlocks() {
        Random rnd = new Random(576);
        for (int t = 0; t < 200; t++) {
            int[] b = randomBlock(rnd);
            assertArrayEquals(oracleForward(b), fwd(b), EPS);
        }
    }

    @Test
    void forwardThenInverseReturnsInput() {
        Random rnd = new Random(1);
        double[] back = new double[64];
        for (int t = 0; t < 200; t++) {
            int[] b = randomBlock(rnd);
            Dct.inverse(fwd(b), back, new double[64]);
            for (int i = 0; i < 64; i++) {
                assertEquals(b[i], back[i], EPS);
            }
        }
    }

    @Test
    void flatBlockOf200HasDc576AndNoAc() {
        int[] b = new int[64];
        java.util.Arrays.fill(b, 200);
        double[] c = fwd(b);
        assertEquals(576.0, c[0], EPS); // 64 * (200 - 128) / 8
        for (int i = 1; i < 64; i++) {
            assertEquals(0.0, c[i], EPS);
        }
    }

    @Test
    void flatBlockDcIsEightTimesShiftedValue() {
        for (int v = 0; v <= 255; v += 17) {
            int[] b = new int[64];
            java.util.Arrays.fill(b, v);
            assertEquals(8.0 * (v - 128), fwd(b)[0], EPS);
        }
    }

    @Test
    void extremeBlocksStayInsideInt16() {
        int[] black = new int[64];
        int[] white = new int[64];
        java.util.Arrays.fill(white, 255);
        assertEquals(-1024.0, fwd(black)[0], EPS); // the largest |DC|, fits a short (P0-D1)
        assertEquals(1016.0, fwd(white)[0], EPS);
    }

    @Test
    void singleHorizontalCosineLandsInOneCoefficient() {
        // Pure horizontal frequency u = 3, v = 0, amplitude 50 on every row.
        // Expected coefficient: 1/4 * C(3) * C(0) * 8 rows * 50 * sum_x cos^2 = 1/4 * 1/sqrt2 * 8 * 50 * 4.
        double expected = 400 / Math.sqrt(2);
        double[] coef = new double[64];
        double[] pix = new double[64];
        coef[3] = expected;
        Dct.inverse(coef, pix, new double[64]);
        int[] b = new int[64];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                double want = 128 + 50 * Math.cos((2 * x + 1) * 3 * Math.PI / 16);
                assertEquals(want, pix[y * 8 + x], EPS); // inverse places the pattern exactly
                b[y * 8 + x] = (int) Math.round(pix[y * 8 + x]);
            }
        }
        // Forward on the integer version: rounding moves each pixel by <= 0.5, so by Parseval
        // (orthonormal) the total error energy is <= 64 * 0.25 = 16 and no coefficient moves by more than 4.
        double[] c = fwd(b);
        assertEquals(expected, c[3], 4.0);
        for (int i = 0; i < 64; i++) {
            if (i != 3) {
                assertTrue(Math.abs(c[i]) < 4.0, "leak at " + i + ": " + c[i]);
            }
        }
    }

    @Test
    void readsBlockAtOffsetInsideLargerPlane() {
        Random rnd = new Random(7);
        int stride = 24;
        int[] plane = new int[stride * 16];
        for (int i = 0; i < plane.length; i++) {
            plane[i] = rnd.nextInt(256);
        }
        int[] block = new int[64];
        for (int y = 0; y < 8; y++) {
            System.arraycopy(plane, (8 + y) * stride + 16, block, y * 8, 8);
        }
        double[] out = new double[64];
        Dct.forward(plane, stride, 16, 8, out, new double[64]);
        assertArrayEquals(oracleForward(block), out, EPS);
    }

    @Test
    void zeroSkipInverseIsBitIdenticalToPlainInverse() {
        Random rnd = new Random(42);
        double[] a = new double[64];
        double[] b = new double[64];
        double[] tmp = new double[64];
        for (int t = 0; t < 500; t++) {
            double[] coef = new double[64];
            int keepRows = rnd.nextInt(9); // 0..8 nonzero rows, rest zero, like quantized data
            for (int v = 0; v < keepRows; v++) {
                for (int u = 0; u < 8; u++) {
                    coef[v * 8 + u] = rnd.nextBoolean() ? 0 : (rnd.nextInt(129) - 64) * 4;
                }
            }
            Dct.inverse(coef, a, tmp);
            Dct.inverseZeroSkip(coef, b, tmp);
            assertArrayEquals(a, b, 0.0);
        }
    }
}
