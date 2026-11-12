package codec.decoder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codec.common.CmpFormat;
import codec.common.CmpFormat.Header;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrameCacheTest {

    @TempDir
    Path dir;

    private static final Header SMALL = new Header(CmpFormat.FLAG_ZLIB, 30, 10, 32, 16, 30, 1, 0, 1, 3, 0);
    private static final int FRAMES = 120;

    /** 120 different frames, so stepping from 100 back to 50 is possible. */
    private Path writeClip() throws IOException {
        Path p = dir.resolve("clip.cmp");
        Random rnd = new Random(30);
        try (CmpFormat.Writer w = new CmpFormat.Writer(p, SMALL)) {
            for (int f = 0; f < FRAMES; f++) {
                byte[] types = new byte[SMALL.blockCount()];
                short[] coef = new short[SMALL.blockCount() * CmpFormat.COEF_PER_BLOCK];
                for (int b = 0; b < types.length; b++) {
                    types[b] = (byte) rnd.nextInt(2);
                }
                for (int i = 0; i < coef.length; i++) {
                    coef[i] = (short) (rnd.nextInt(61) - 30);
                }
                w.writeFrame(types, coef);
            }
        }
        return p;
    }

    /** Same pixels and block types as a fresh serial decode of frame k. */
    private static void assertForwardPixels(CmpFormat.Reader r, int k, FrameCache.Entry e) throws IOException {
        DecodeCore.Decoded want = DecodeCore.decode(r.header(), r.payload(k));
        assertEquals(k, e.index());
        assertArrayEquals(want.frame().r(), e.toFrame().r(), "R frame " + k);
        assertArrayEquals(want.frame().g(), e.toFrame().g(), "G frame " + k);
        assertArrayEquals(want.frame().b(), e.toFrame().b(), "B frame " + k);
        assertArrayEquals(want.types(), e.types(), "types frame " + k);
    }

    @Test
    void stepBackOneIsServedFromTheRing() throws Exception {
        try (CmpFormat.Reader r = new CmpFormat.Reader(writeClip());
             DecodePipeline p = new DecodePipeline(r, 3, 4)) {
            FrameCache c = new FrameCache(p, 30);
            for (int k = 0; k <= 40; k++) {
                c.get(k);
            }
            long decodedBefore = p.decodedCount();
            FrameCache.Entry back = c.get(39);
            assertForwardPixels(r, 39, back);
            assertEquals(1, c.hits(), "step back 1 must not decode");
            assertSame(back, c.get(39), "second request returns the same entry");
            assertTrue(p.decodedCount() - decodedBefore <= p.capacity(),
                    "no restart: at most the normal forward prefetch ran");
        }
    }

    @Test
    void stepBackFiftyFrom100CrossesTheCacheBoundaryCorrectly() throws Exception {
        try (CmpFormat.Reader r = new CmpFormat.Reader(writeClip());
             DecodePipeline p = new DecodePipeline(r, 3, 4)) {
            FrameCache c = new FrameCache(p, 30);
            for (int k = 0; k <= 100; k++) {
                c.get(k);
            }
            assertTrue(c.contains(71) && c.contains(100) && !c.contains(70), "ring holds 71..100");
            long hits0 = c.hits();
            long miss0 = c.misses();
            for (int k = 99; k >= 50; k--) {
                assertForwardPixels(r, k, c.get(k));
            }
            assertEquals(29, c.hits() - hits0, "99..71 come from the ring");
            assertEquals(21, c.misses() - miss0, "70..50 are decoded again via footer offsets");
            // The frames just re-decoded are now the newest in the ring: stepping forward is instant.
            long hits1 = c.hits();
            for (int k = 51; k <= 60; k++) {
                assertForwardPixels(r, k, c.get(k));
            }
            assertEquals(10, c.hits() - hits1);
        }
    }

    @Test
    void oldestInsertedIsEvictedAndBuffersAreReused() throws Exception {
        try (CmpFormat.Reader r = new CmpFormat.Reader(writeClip());
             DecodePipeline p = new DecodePipeline(r, 2, 4)) {
            FrameCache c = new FrameCache(p, 3);
            int[] first = c.get(0).rgb();
            c.get(1);
            c.get(2);
            FrameCache.Entry e3 = c.get(3);
            assertFalse(c.contains(0), "frame 0 was the oldest");
            assertSame(first, e3.rgb(), "evicted buffer reused, no new allocation");
            assertForwardPixels(r, 3, e3);
        }
    }

    @Test
    void memoryFigureFor30Frames1080p() {
        Header hd = new Header(0, 1920, 1080, 1920, 1088, 30, 1, 0, 2, 5, 0);
        long bytes = FrameCache.bytesFor(hd, FrameCache.DEFAULT_CAPACITY);
        assertEquals(30L * (1920 * 1080 * 4 + 240 * 136), bytes);
        assertTrue(bytes > 245_000_000L && bytes < 255_000_000L, "about 250 MB, got " + bytes);
    }

    @Test
    void packedPixelsKeepEveryValue() throws Exception {
        try (CmpFormat.Reader r = new CmpFormat.Reader(writeClip());
             DecodePipeline p = new DecodePipeline(r, 2, 2)) {
            FrameCache.Entry e = new FrameCache(p).get(7);
            for (int px : e.rgb()) {
                assertEquals(0, px >>> 24, "top byte must stay empty");
            }
            assertForwardPixels(r, 7, e);
        }
    }

    @Test
    void badCapacityRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FrameCache(null, 0));
    }
}
