package codec.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codec.common.CmpFormat.CmpException;
import codec.common.CmpFormat.Header;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** P4-B zigzag + run-length (.cmp mode 1): order, lossless round trip, size, and corrupt input. */
class RunLengthTest {

    /** 32x16 padded: 4 x 2 = 8 blocks of 8x8. */
    private static final Header H = new Header(CmpFormat.FLAG_ZLIB | (CmpFormat.MODE_RLE << CmpFormat.MODE_SHIFT),
            30, 10, 32, 16, 30, 1, 0, 2, 5, 0);

    private static short[] sparse(Random r, double nonzero) {
        short[] c = new short[H.blockCount() * CmpFormat.COEF_PER_BLOCK];
        for (int i = 0; i < c.length; i++) {
            if (r.nextDouble() < nonzero) {
                c[i] = (short) (r.nextInt(2001) - 1000);
            }
        }
        return c;
    }

    private static byte[] types(Random r) {
        byte[] t = new byte[H.blockCount()];
        for (int b = 0; b < t.length; b++) {
            t[b] = (byte) r.nextInt(2);
        }
        return t;
    }

    @Test
    void zigzagIsTheJpegOrderAndVisitsEveryCoefficientOnce() {
        int[] z = RunLength.ZIGZAG;
        assertArrayEquals(new int[] {0, 1, 8, 16, 9, 2, 3, 10, 17, 24}, java.util.Arrays.copyOf(z, 10));
        assertEquals(63, z[63]);
        boolean[] seen = new boolean[64];
        for (int i : z) {
            seen[i] = true;
        }
        for (boolean s : seen) {
            assertTrue(s);
        }
    }

    @Test
    void roundTripIsLosslessForSparseDenseAndExtremeValues() throws Exception {
        Random r = new Random(4);
        for (double density : new double[] {0.0, 0.05, 0.5, 1.0}) {
            short[] c = sparse(r, density);
            c[0] = Short.MIN_VALUE;
            c[1] = Short.MAX_VALUE;
            byte[] t = types(r);
            byte[] enc = RunLength.encode(H, t, c);
            byte[] t2 = new byte[t.length];
            short[] c2 = new short[c.length];
            java.util.Arrays.fill(c2, (short) 7); // decode must overwrite every coefficient
            RunLength.decode(H, enc, t2, c2);
            assertArrayEquals(t, t2, "types at density " + density);
            assertArrayEquals(c, c2, "coefficients at density " + density);
        }
    }

    @Test
    void allZeroChannelCostsOneByteInsteadOf128() throws Exception {
        byte[] enc = RunLength.encode(H, new byte[H.blockCount()], new short[H.blockCount() * CmpFormat.COEF_PER_BLOCK]);
        assertEquals(H.blockCount() * (1 + 3), enc.length, "type byte + one end marker per channel");
        assertTrue(enc.length * 30 < H.baselineRawBytes(), "about 96x smaller than the baseline layout");
    }

    @Test
    void lastCoefficientAloneUsesARunOf63() throws Exception {
        short[] c = new short[H.blockCount() * CmpFormat.COEF_PER_BLOCK];
        c[63] = 5; // block 0, red, natural index 63 = zigzag index 63
        byte[] enc = RunLength.encode(H, new byte[H.blockCount()], c);
        assertEquals(0, enc[0]); // type
        assertEquals(63, enc[1]); // run of 63 zeros
        assertEquals(5, enc[3]); // value low byte
        assertEquals((byte) RunLength.EOB, enc[4]);
    }

    @Test
    void corruptPayloadsGiveClearErrors() throws Exception {
        Random r = new Random(9);
        byte[] good = RunLength.encode(H, types(r), sparse(r, 0.1));
        byte[] t = new byte[H.blockCount()];
        short[] c = new short[H.blockCount() * CmpFormat.COEF_PER_BLOCK];
        CmpException truncated = assertThrows(CmpException.class,
                () -> RunLength.decode(H, java.util.Arrays.copyOf(good, good.length - 3), t, c));
        assertTrue(truncated.getMessage().contains("ends early"), truncated.getMessage());
        byte[] extra = java.util.Arrays.copyOf(good, good.length + 2);
        assertTrue(assertThrows(CmpException.class, () -> RunLength.decode(H, extra, t, c))
                .getMessage().contains("extra bytes"));
        byte[] badType = good.clone();
        badType[0] = 2;
        assertTrue(assertThrows(CmpException.class, () -> RunLength.decode(H, badType, t, c))
                .getMessage().contains("block_type 2"));
        byte[] overrun = {0, 64, 0, 1};
        assertTrue(assertThrows(CmpException.class, () -> RunLength.decode(H, overrun, t, c))
                .getMessage().contains("passes coefficient 63"));
    }
}
