package codec.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codec.common.CmpFormat.CmpException;
import codec.common.CmpFormat.Header;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** P4-A core: drift-free reconstruction, cheaper P frames, quality, and clear errors. */
class McCodecTest {

    /** True 60x40, padded 64x48: 4 x 3 macroblocks, 8 x 6 blocks. */
    static Header header(int n1, int n2) {
        return new Header(CmpFormat.FLAG_ZLIB | (CmpFormat.MODE_MC << CmpFormat.MODE_SHIFT),
                60, 40, 64, 48, 30, 1, 0, n1, n2, 0);
    }

    /** Padded frame t of a smooth texture moving right 2 px and down 1 px per frame. */
    static Types.Frame frame(int t) {
        Types.Frame f = Types.Frame.allocate(64, 48);
        for (int y = 0; y < 48; y++) {
            for (int x = 0; x < 64; x++) {
                int u = x - 2 * t;
                int v = y - t;
                int i = y * 64 + x;
                f.r()[i] = (int) (128 + 90 * Math.sin(u * 0.21) * Math.cos(v * 0.17));
                f.g()[i] = (int) (128 + 60 * Math.cos(u * 0.13 + v * 0.11));
                f.b()[i] = (int) (128 + 80 * Math.sin((u + v) * 0.09));
            }
        }
        return f;
    }

    /** Every macroblock moved by the true motion: block at (x, y) came from (x - 2, y - 1). */
    static Types.MotionField motion() {
        short[] dx = new short[12];
        short[] dy = new short[12];
        java.util.Arrays.fill(dx, (short) -2);
        java.util.Arrays.fill(dy, (short) -1);
        return new Types.MotionField(4, 3, dx, dy);
    }

    static byte[] types(Random r, int blocks) {
        byte[] t = new byte[blocks];
        for (int b = 0; b < blocks; b++) {
            t[b] = (byte) r.nextInt(2);
        }
        return t;
    }

    static double psnr(int[] a, int[] b, int w, int h, int stride) {
        double se = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double d = a[y * stride + x] - b[y * stride + x];
                se += d * d;
            }
        }
        return se == 0 ? 99 : 10 * Math.log10(255.0 * 255 / (se / (w * h)));
    }

    @Test
    void encoderAndDecoderReferencesStayBitIdenticalOverSeveralGroups() throws Exception {
        Header h = header(2, 5);
        McCodec.State enc = new McCodec.State(64, 48);
        McCodec.State dec = new McCodec.State(64, 48);
        Random r = new Random(5);
        byte[] types = new byte[h.blockCount()];
        short[] coef = new short[h.blockCount() * CmpFormat.COEF_PER_BLOCK];
        for (int k = 0; k < 3 * McCodec.GOP + 3; k++) {
            byte[] t = types(r, h.blockCount());
            byte[] payload = McCodec.encode(h, enc, frame(k), t, motion(), k);
            McCodec.decode(h, dec, payload, k, types, coef);
            assertArrayEquals(t, types, "types frame " + k);
            for (int ch = 0; ch < 3; ch++) {
                assertArrayEquals(enc.ref[ch], dec.ref[ch], "drift in frame " + k + " channel " + ch);
            }
        }
    }

    @Test
    void predictedFramesAreMuchSmallerThanIntraFrames() throws Exception {
        Header h = header(2, 5);
        McCodec.State st = new McCodec.State(64, 48);
        byte[] t = new byte[h.blockCount()];
        int iSize = McCodec.encode(h, st, frame(0), t, null, 0).length;
        int pSize = 0;
        for (int k = 1; k < McCodec.GOP; k++) {
            pSize += McCodec.encode(h, st, frame(k), t, motion(), k).length;
        }
        double meanP = pSize / (McCodec.GOP - 1.0);
        assertTrue(meanP * 2 < iSize, "P frames should be well under half an I frame: I " + iSize + ", P mean " + meanP);
    }

    @Test
    void atN0EveryFrameIsNearlyExactSoNothingDrifts() throws Exception {
        Header h = header(0, 0);
        McCodec.State st = new McCodec.State(64, 48);
        byte[] t = new byte[h.blockCount()];
        for (int k = 0; k < 2 * McCodec.GOP; k++) {
            McCodec.encode(h, st, frame(k), t, motion(), k);
            Types.Frame f = frame(k);
            assertTrue(psnr(f.r(), st.ref[0], 60, 40, 64) > 45, "frame " + k);
            assertTrue(psnr(f.b(), st.ref[2], 60, 40, 64) > 45, "frame " + k);
        }
    }

    @Test
    void layersStillWorkInsideResiduals() throws Exception {
        Header h = header(1, 7);
        McCodec.State st = new McCodec.State(64, 48);
        byte[] t = new byte[h.blockCount()];
        for (int b = 0; b < t.length; b++) {
            t[b] = (byte) ((b % 8) < 4 ? 1 : 0); // left half foreground
        }
        Types.Frame f = null;
        for (int k = 0; k < 6; k++) {
            f = frame(k);
            McCodec.encode(h, st, f, t, motion(), k);
        }
        double fg = psnr(f.g(), st.ref[1], 32, 40, 64);
        int[] shifted = new int[64 * 48];
        int[] shiftedRef = new int[64 * 48];
        for (int y = 0; y < 48; y++) {
            System.arraycopy(f.g(), y * 64 + 32, shifted, y * 64, 32);
            System.arraycopy(st.ref[1], y * 64 + 32, shiftedRef, y * 64, 32);
        }
        double bg = psnr(shifted, shiftedRef, 28, 40, 64);
        assertTrue(fg > bg + 5, "foreground " + fg + " dB should beat background " + bg + " dB");
    }

    @Test
    void wrongOrderAndBadInputGiveClearErrors() throws Exception {
        Header h = header(2, 5);
        McCodec.State st = new McCodec.State(64, 48);
        byte[] t = new byte[h.blockCount()];
        byte[] i0 = McCodec.encode(h, st, frame(0), t, null, 0);
        byte[] p1 = McCodec.encode(h, st, frame(1), t, motion(), 1);
        short[] coef = new short[h.blockCount() * CmpFormat.COEF_PER_BLOCK];
        McCodec.State fresh = new McCodec.State(64, 48);
        assertTrue(assertThrows(CmpException.class, () -> McCodec.decode(h, fresh, p1, 1, t, coef))
                .getMessage().contains("must start at an I frame"));
        assertTrue(assertThrows(CmpException.class, () -> McCodec.decode(h, fresh, i0, 1, t, coef))
                .getMessage().contains("expected P"));
        short[] far = new short[12];
        far[0] = 200;
        Types.MotionField bad = new Types.MotionField(4, 3, far, new short[12]);
        assertTrue(assertThrows(CmpException.class, () -> McCodec.encode(h, st, frame(3), t, bad, 3)) // 3 is a P frame at GOP 2
                .getMessage().contains("outside -128..127"));
        assertTrue(assertThrows(CmpException.class, () -> McCodec.decode(h, fresh, new byte[0], 0, t, coef))
                .getMessage().contains("empty"));
    }

    @Test
    void groupLengthIsTwo() {
        assertEquals(2, McCodec.GOP); // P4-L3
        assertTrue(McCodec.isIntra(0) && McCodec.isIntra(2) && McCodec.isIntra(8) && !McCodec.isIntra(7) && !McCodec.isIntra(1));
    }
}
