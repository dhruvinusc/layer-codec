package codec.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ZioTest {

    private static byte[] roundTrip(byte[] raw) throws IOException {
        byte[] c = Zio.deflate(raw, 0, raw.length);
        byte[] back = new byte[raw.length];
        Zio.inflate(c, 0, c.length, back, raw.length);
        return back;
    }

    @Test
    void randomBytesRoundTripEvenThoughTheyDoNotCompress() throws IOException {
        byte[] raw = new byte[100_000];
        new Random(1).nextBytes(raw);
        assertArrayEquals(raw, roundTrip(raw));
    }

    @Test
    void emptyInputRoundTrips() throws IOException {
        assertArrayEquals(new byte[0], roundTrip(new byte[0]));
    }

    @Test
    void quantizedCoefficientsCompressWell() throws IOException {
        // A smooth gradient image, DCT + quantize at n=4, serialized as big-endian shorts like .cmp.
        int w = 64;
        int[] plane = new int[w * w];
        for (int y = 0; y < w; y++) {
            for (int x = 0; x < w; x++) {
                plane[y * w + x] = (x * 3 + y * 2) & 0xFF;
            }
        }
        byte[] raw = new byte[(w / 8) * (w / 8) * 64 * 2];
        double[] coef = new double[64];
        double[] tmp = new double[64];
        short[] q = new short[64];
        int p = 0;
        for (int by = 0; by < w; by += 8) {
            for (int bx = 0; bx < w; bx += 8) {
                Dct.forward(plane, w, bx, by, coef, tmp);
                Quant.quantizeBlock(coef, 4, q, 0);
                for (short s : q) {
                    raw[p++] = (byte) (s >> 8);
                    raw[p++] = (byte) s;
                }
            }
        }
        byte[] c = Zio.deflate(raw, 0, raw.length);
        assertTrue(raw.length / (double) c.length > 5, "ratio " + raw.length / (double) c.length);
        assertArrayEquals(raw, roundTrip(raw));
    }

    @Test
    void truncatedStreamIsAnError() {
        byte[] raw = new byte[10_000];
        new Random(2).nextBytes(raw);
        byte[] c = Zio.deflate(raw, 0, raw.length);
        byte[] cut = Arrays.copyOf(c, c.length / 2);
        assertThrows(IOException.class, () -> Zio.inflate(cut, 0, cut.length, new byte[raw.length], raw.length));
    }

    @Test
    void wrongExpectedLengthIsAnError() {
        byte[] raw = new byte[5000];
        byte[] c = Zio.deflate(raw, 0, raw.length);
        assertThrows(IOException.class, () -> Zio.inflate(c, 0, c.length, new byte[6000], 6000));
        assertThrows(IOException.class, () -> Zio.inflate(c, 0, c.length, new byte[4000], 4000));
    }

    @Test
    void garbageIsAnError() {
        byte[] junk = {1, 2, 3, 4, 5, 6, 7, 8};
        assertThrows(IOException.class, () -> Zio.inflate(junk, 0, junk.length, new byte[100], 100));
    }
}
