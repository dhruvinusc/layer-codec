package codec.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import codec.common.Types.Frame;
import org.junit.jupiter.api.Test;

class PadTest {

    private static Frame ramp(int w, int h) {
        Frame f = Frame.allocate(w, h);
        for (int i = 0; i < w * h; i++) {
            f.r()[i] = i % 256;
            f.g()[i] = (i * 7) % 256;
            f.b()[i] = 255 - i % 256;
        }
        return f;
    }

    @Test
    void sizesFromTheTestClips() {
        assertEquals(1088, Types.paddedSize(1080));
        assertEquals(1920, Types.paddedSize(1920));
        assertEquals(848, Types.paddedSize(848));
        assertEquals(480, Types.paddedSize(480));
        assertEquals(16, Types.paddedSize(1));
    }

    @Test
    void hd1080PadsBottomRowsByReplication() {
        Frame src = ramp(1920, 1080);
        Frame p = Pad.toMacroblocks(src);
        assertEquals(1920, p.width());
        assertEquals(1088, p.height());
        for (int y = 1080; y < 1088; y++) {
            for (int x = 0; x < 1920; x += 97) {
                assertEquals(src.g()[1079 * 1920 + x], p.g()[y * 1920 + x]);
            }
        }
        assertEquals(src.r()[1079 * 1920 + 1919], p.r()[1087 * 1920 + 1919]);
    }

    @Test
    void alreadyAlignedIsAnExactCopy() {
        Frame src = ramp(480, 848);
        Frame p = Pad.toMacroblocks(src);
        assertEquals(480, p.width());
        assertEquals(848, p.height());
        assertArrayEquals(src.r(), p.r());
        assertArrayEquals(src.b(), p.b());
    }

    @Test
    void rightAndBottomCornerReplicateLastPixel() {
        Frame src = ramp(5, 3);
        Frame p = Pad.toMacroblocks(src);
        assertEquals(16, p.width());
        assertEquals(16, p.height());
        assertEquals(src.r()[0 * 5 + 4], p.r()[0 * 16 + 15]);
        assertEquals(src.r()[2 * 5 + 4], p.r()[15 * 16 + 15]);
        assertEquals(src.r()[2 * 5 + 0], p.r()[15 * 16 + 0]);
    }

    @Test
    void cropUndoesPad() {
        Frame src = ramp(37, 21);
        Frame back = Pad.crop(Pad.toMacroblocks(src), 37, 21);
        assertArrayEquals(src.r(), back.r());
        assertArrayEquals(src.g(), back.g());
        assertArrayEquals(src.b(), back.b());
    }
}
