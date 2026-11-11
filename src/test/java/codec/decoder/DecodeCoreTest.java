package codec.decoder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codec.common.CmpFormat;
import codec.common.CmpFormat.CmpException;
import codec.common.CmpFormat.Header;
import codec.common.Dct;
import codec.common.Pad;
import codec.common.Quant;
import codec.common.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class DecodeCoreTest {

    /** True 30x10 -> padded 32x16, 8 blocks, so the crop path is exercised in both directions. */
    private static Header header(int n1, int n2) {
        return new Header(0, 30, 10, 32, 16, 30, 1, 0, n1, n2, 0);
    }

    /** Smooth, textured test picture with different content per channel. */
    private static Types.Frame picture(int w, int h) {
        Types.Frame f = Types.Frame.allocate(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                f.r()[i] = (x * 7 + y * 3) & 0xFF;
                f.g()[i] = (int) (128 + 100 * Math.sin(x * 0.4) * Math.cos(y * 0.3));
                f.b()[i] = (x * y) & 0xFF;
            }
        }
        return f;
    }

    /** Test-side encoder: pad, DCT each channel, quantize by type (the textbook pipeline). */
    private static byte[] encode(Header h, Types.Frame frame, byte[] types) throws CmpException {
        Types.Frame p = Pad.pad(frame, h.paddedW(), h.paddedH());
        short[] coef = new short[h.blockCount() * CmpFormat.COEF_PER_BLOCK];
        double[] c = new double[64];
        double[] tmp = new double[64];
        for (int b = 0; b < h.blockCount(); b++) {
            int x0 = (b % h.blocksX()) * 8;
            int y0 = (b / h.blocksX()) * 8;
            int n = types[b] == 1 ? h.n1() : h.n2();
            int[][] planes = {p.r(), p.g(), p.b()};
            for (int ch = 0; ch < 3; ch++) {
                Dct.forward(planes[ch], h.paddedW(), x0, y0, c, tmp);
                Quant.quantizeBlock(c, n, coef, b * CmpFormat.COEF_PER_BLOCK + ch * 64);
            }
        }
        return CmpFormat.encodeBaseline(h, types, coef);
    }

    private static int maxError(Types.Frame a, Types.Frame b) {
        int m = 0;
        for (int i = 0; i < a.r().length; i++) {
            m = Math.max(m, Math.abs(a.r()[i] - b.r()[i]));
            m = Math.max(m, Math.abs(a.g()[i] - b.g()[i]));
            m = Math.max(m, Math.abs(a.b()[i] - b.b()[i]));
        }
        return m;
    }

    @Test
    void nZeroRoundTripIsNearlyExactAndCropped() throws CmpException {
        Header h = header(0, 0);
        Types.Frame src = picture(30, 10);
        DecodeCore.Decoded d = DecodeCore.decode(h, encode(h, src, new byte[8]));
        assertEquals(30, d.frame().width());
        assertEquals(10, d.frame().height());
        assertTrue(maxError(src, d.frame()) <= 2, "max " + maxError(src, d.frame()));
    }

    @Test
    void storedThreeRebuildsAs12InForegroundAnd96InBackground() throws CmpException {
        // Lesson prediction: n1 = 2, n2 = 5, only the R DC holds 3. Block 0 foreground, block 1 background.
        Header h = header(2, 5);
        byte[] types = new byte[8];
        types[0] = 1;
        short[] coef = new short[8 * CmpFormat.COEF_PER_BLOCK];
        coef[0] = 3;
        coef[CmpFormat.COEF_PER_BLOCK] = 3;
        Types.Frame f = DecodeCore.decode(h, CmpFormat.encodeBaseline(h, types, coef)).frame();
        // A flat block with DC d has pixels 128 + d / 8: 128 + 12/8 = 129.5 -> 130; 128 + 96/8 = 140.
        assertEquals(130, f.r()[0]);
        assertEquals(140, f.r()[8]);
        assertEquals(128, f.g()[0]); // G and B were all zero: mid gray
    }

    @Test
    void blockTypeChoosesTheExponent() throws CmpException {
        Header h = header(0, 8);
        Types.Frame src = picture(30, 10);
        byte[] fg = {1, 1, 1, 1, 1, 1, 1, 1};
        byte[] bg = new byte[8];
        int fgErr = maxError(src, DecodeCore.decode(h, encode(h, src, fg)).frame());
        int bgErr = maxError(src, DecodeCore.decode(h, encode(h, src, bg)).frame());
        assertTrue(fgErr <= 2, "fg " + fgErr);
        assertTrue(bgErr > 10, "bg " + bgErr);
        Header swapped = header(8, 0); // swapping n1 and n2 swaps which blocks are clean
        assertTrue(maxError(src, DecodeCore.decode(swapped, encode(swapped, src, bg)).frame()) <= 2);
    }

    @Test
    void returnsTheBlockTypeMap() throws CmpException {
        Header h = header(1, 4);
        byte[] types = {0, 1, 1, 0, 1, 0, 0, 1};
        DecodeCore.Decoded d = DecodeCore.decode(h, encode(h, picture(30, 10), types));
        assertArrayEquals(types, d.types());
    }

    @Test
    void parallelRowsMatchSerialExactly() throws Exception {
        Header h = new Header(0, 120, 70, 128, 80, 30, 1, 0, 1, 6, 0);
        byte[] types = new byte[h.blockCount()];
        for (int i = 0; i < types.length; i++) {
            types[i] = (byte) ((i / 3) % 2);
        }
        byte[] raw = encode(h, picture(120, 70), types);
        Types.Frame serial = DecodeCore.decode(h, raw).frame();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Types.Frame par = DecodeCore.decodeParallel(h, raw, pool, 7).frame();
            assertArrayEquals(serial.r(), par.r());
            assertArrayEquals(serial.g(), par.g());
            assertArrayEquals(serial.b(), par.b());
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void manyThreadsDecodingAtOnceAgree() throws Exception {
        Header h = new Header(0, 120, 70, 128, 80, 30, 1, 0, 2, 5, 0);
        byte[] raw = encode(h, picture(120, 70), new byte[h.blockCount()]);
        Types.Frame ref = DecodeCore.decode(h, raw).frame();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Types.Frame>> fs = new ArrayList<>();
            for (int t = 0; t < 16; t++) {
                fs.add(pool.submit(() -> DecodeCore.decode(h, raw, new DecodeCore.Scratch(h)).frame()));
            }
            for (Future<Types.Frame> f : fs) {
                assertArrayEquals(ref.r(), f.get().r());
                assertArrayEquals(ref.b(), f.get().b());
            }
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void badBlockTypeIsRejected() {
        Header h = header(1, 4);
        byte[] raw = new byte[h.baselineRawBytes()];
        raw[2 * CmpFormat.BYTES_PER_BLOCK] = 5;
        assertThrows(CmpException.class, () -> DecodeCore.decode(h, raw));
    }
}
