package codec.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codec.common.CmpFormat.CmpException;
import codec.common.CmpFormat.Header;
import codec.decoder.DecodeCore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class FrameEncoderTest {

    /** True 40x20 -> padded 48x32: 3 x 2 macroblocks, 6 x 4 blocks. */
    private static Header header(int n1, int n2) {
        return new Header(0, 40, 20, 48, 32, 30, 1, 0, n1, n2, 0);
    }

    private static Types.Frame picture(int w, int h) {
        Types.Frame f = Types.Frame.allocate(w, h);
        for (int i = 0; i < w * h; i++) {
            int x = i % w;
            int y = i / w;
            f.r()[i] = (x * 5 + y * 9) & 0xFF;
            f.g()[i] = (int) (128 + 90 * Math.sin(x * 0.5 + y * 0.2));
            f.b()[i] = (x ^ y) * 4 & 0xFF;
        }
        return f;
    }

    private static Types.Labels labels(int... ids) {
        byte[] b = new byte[ids.length];
        for (int i = 0; i < ids.length; i++) {
            b[i] = (byte) ids[i];
        }
        return new Types.Labels(3, 2, b);
    }

    @Test
    void roundTripAtN0IsNearlyExact() throws CmpException {
        Header h = header(0, 0);
        Types.Frame src = picture(40, 20);
        Types.Frame dec = DecodeCore.decode(h, FrameEncoder.encode(h, src, labels(0, 1, 0, 2, 0, 9))).frame();
        int max = 0;
        for (int i = 0; i < src.r().length; i++) {
            max = Math.max(max, Math.abs(src.r()[i] - dec.r()[i]));
            max = Math.max(max, Math.abs(src.g()[i] - dec.g()[i]));
            max = Math.max(max, Math.abs(src.b()[i] - dec.b()[i]));
        }
        assertTrue(max <= 2, "max " + max);
    }

    @Test
    void blockTypesFollowLabelsAndRegionIdsCollapseToOne() throws CmpException {
        Header h = header(1, 6);
        byte[] raw = FrameEncoder.encode(h, picture(40, 20), labels(0, 7, 0, 0, 255, 0));
        byte[] types = DecodeCore.decode(h, raw).types();
        // Macroblock column 1 is foreground in both macroblock rows (labels 7 and 255) -> 8x8 columns 2..3.
        byte[] expected = {
            0, 0, 1, 1, 0, 0,
            0, 0, 1, 1, 0, 0,
            0, 0, 1, 1, 0, 0,
            0, 0, 1, 1, 0, 0};
        assertArrayEquals(expected, types);
    }

    @Test
    void acceptsAlreadyPaddedFrameWithSameBytes() throws CmpException {
        Header h = header(2, 5);
        Types.Frame src = picture(40, 20);
        Types.Labels l = labels(1, 0, 1, 0, 1, 0);
        assertArrayEquals(FrameEncoder.encode(h, src, l),
                FrameEncoder.encode(h, Pad.pad(src, 48, 32), l));
    }

    @Test
    void parallelGivesIdenticalBytes() throws Exception {
        Header h = new Header(0, 200, 90, 208, 96, 30, 1, 0, 2, 5, 0);
        byte[] ids = new byte[13 * 6];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = (byte) (i % 3 == 0 ? 1 : 0);
        }
        Types.Labels l = new Types.Labels(13, 6, ids);
        Types.Frame src = picture(200, 90);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            assertArrayEquals(FrameEncoder.encode(h, src, l), FrameEncoder.encodeParallel(h, src, l, pool, 5));
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void wrongSizesAreRejected() {
        Header h = header(2, 5);
        assertThrows(CmpException.class, () -> FrameEncoder.encode(h, picture(41, 20), labels(0, 0, 0, 0, 0, 0)));
        assertThrows(CmpException.class,
                () -> FrameEncoder.encode(h, picture(40, 20), new Types.Labels(2, 2, new byte[4])));
    }
}
