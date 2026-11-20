package codec.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class QuantTest {

    @Test
    void workedExamplesFromTheLessonAtN3() {
        assertEquals(2, Quant.quantize(13, 3));
        assertEquals(16.0, Quant.dequantize((short) 2, 3));
        assertEquals(-2, Quant.quantize(-12, 3)); // -1.5 rounds away from zero
        assertEquals(0, Quant.quantize(3, 3));    // small detail becomes 0
        assertEquals(72, Quant.quantize(576, 3)); // flat DC survives exactly
        assertEquals(576.0, Quant.dequantize((short) 72, 3));
        assertEquals(1, Quant.quantize(5, 3));
        assertEquals(0, Quant.quantize(2, 3));
    }

    @Test
    void halvesRoundAwayFromZeroSymmetrically() {
        assertEquals(1, Quant.quantize(4, 3));    // 0.5
        assertEquals(-1, Quant.quantize(-4, 3));
        assertEquals(3, Quant.quantize(20, 3));   // 2.5
        assertEquals(-3, Quant.quantize(-20, 3));
        Random rnd = new Random(3);
        for (int t = 0; t < 10000; t++) {
            double c = (rnd.nextDouble() - 0.5) * 2048;
            int n = rnd.nextInt(Quant.MAX_N + 1);
            assertEquals(-Quant.quantize(c, n), Quant.quantize(-c, n));
        }
    }

    @Test
    void errorIsAtMostHalfAStepAndGrowsWithN() {
        Random rnd = new Random(11);
        double prevMean = -1;
        for (int n = Quant.MIN_N; n <= Quant.MAX_N; n++) {
            double sum = 0;
            for (int t = 0; t < 5000; t++) {
                double c = (rnd.nextDouble() - 0.5) * 2048;
                double err = Math.abs(Quant.dequantize(Quant.quantize(c, n), n) - c);
                assertTrue(err <= Quant.step(n) / 2.0 + 1e-9, "n=" + n + " err=" + err);
                sum += err;
            }
            double mean = sum / 5000;
            assertTrue(mean > prevMean, "mean error must grow with n");
            prevMean = mean;
        }
    }

    @Test
    void roundingHasAboutHalfTheErrorOfTruncation() {
        Random rnd = new Random(5);
        int n = 4;
        double round = 0;
        double trunc = 0;
        for (int t = 0; t < 20000; t++) {
            double c = (rnd.nextDouble() - 0.5) * 2048;
            round += Math.abs(Quant.dequantize(Quant.quantize(c, n), n) - c);
            trunc += Math.abs(((long) (c / 16)) * 16.0 - c);
        }
        double ratio = round / trunc;
        assertTrue(ratio > 0.45 && ratio < 0.55, "ratio " + ratio);
    }

    @Test
    void extremeDcFitsAtN0AndOverflowIsCaught() {
        assertEquals(-1024, Quant.quantize(-1024, 0));
        assertEquals(1016, Quant.quantize(1016, 0));
        assertThrows(ArithmeticException.class, () -> Quant.quantize(40000, 0));
    }

    @Test
    void exponentOutsideRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Quant.quantize(1, -1));
        assertThrows(IllegalArgumentException.class, () -> Quant.quantize(1, 11));
    }

    @Test
    void toPixelRoundsAndClamps() {
        assertEquals(0, Quant.toPixel(-3.2));
        assertEquals(255, Quant.toPixel(255.6));
        assertEquals(128, Quant.toPixel(127.5));
        assertEquals(127, Quant.toPixel(127.49));
    }

    @Test
    void blockRoundTripAtN0IsNearlyLossless() {
        // DCT -> quantize n=0 -> dequantize -> IDCT -> pixel. Only rounding is lost (H2: >= 45 dB).
        Random rnd = new Random(9);
        double[] coef = new double[64];
        double[] back = new double[64];
        double[] tmp = new double[64];
        short[] q = new short[64];
        long sq = 0;
        int max = 0;
        int count = 0;
        for (int t = 0; t < 500; t++) {
            int[] b = new int[64];
            for (int i = 0; i < 64; i++) {
                b[i] = rnd.nextInt(256);
            }
            Dct.forward(b, 8, 0, 0, coef, tmp);
            Quant.quantizeBlock(coef, 0, q, 0);
            Quant.dequantizeBlock(q, 0, 0, coef);
            Dct.inverseZeroSkip(coef, back, tmp);
            for (int i = 0; i < 64; i++) {
                int d = Math.abs(Quant.toPixel(back[i]) - b[i]);
                sq += (long) d * d;
                max = Math.max(max, d);
                count++;
            }
        }
        double mse = (double) sq / count;
        double psnr = 10 * Math.log10(255.0 * 255.0 / Math.max(mse, 1e-12));
        assertTrue(max <= 2, "max pixel error " + max);
        assertTrue(psnr >= 45, "psnr " + psnr);
    }
}
