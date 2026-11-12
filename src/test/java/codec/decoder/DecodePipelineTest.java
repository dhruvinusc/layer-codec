package codec.decoder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codec.common.CmpFormat;
import codec.common.CmpFormat.Header;
import codec.common.Types;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DecodePipelineTest {

    @TempDir
    Path dir;

    /** True 30x10, padded 32x16 (crop exercised), zlib on, n1 = 1, n2 = 3. */
    private static final Header SMALL = new Header(CmpFormat.FLAG_ZLIB, 30, 10, 32, 16, 30, 1, 0, 1, 3, 0);
    private static final int FRAMES = 40;

    /** Writes a .cmp whose frames all differ (random coefficients and block types, fixed seed). */
    private Path writeClip() throws IOException {
        Path p = dir.resolve("clip.cmp");
        Random rnd = new Random(576);
        try (CmpFormat.Writer w = new CmpFormat.Writer(p, SMALL)) {
            for (int f = 0; f < FRAMES; f++) {
                byte[] types = new byte[SMALL.blockCount()];
                short[] coef = new short[SMALL.blockCount() * CmpFormat.COEF_PER_BLOCK];
                for (int b = 0; b < types.length; b++) {
                    types[b] = (byte) rnd.nextInt(2);
                }
                for (int i = 0; i < coef.length; i++) {
                    coef[i] = (short) (rnd.nextInt(41) - 20);
                }
                w.writeFrame(types, coef);
            }
        }
        return p;
    }

    private static void assertSameAsSerial(CmpFormat.Reader r, int k, DecodeCore.Decoded got) throws IOException {
        DecodeCore.Decoded want = DecodeCore.decode(r.header(), r.payload(k));
        assertArrayEquals(want.frame().r(), got.frame().r(), "R frame " + k);
        assertArrayEquals(want.frame().g(), got.frame().g(), "G frame " + k);
        assertArrayEquals(want.frame().b(), got.frame().b(), "B frame " + k);
        assertArrayEquals(want.types(), got.types(), "types frame " + k);
    }

    /** Fake source for timing tests: frame k carries its own index in pixel 0. */
    private static final Header TINY = new Header(0, 8, 8, 16, 16, 30, 1, 0, 0, 0, 0);

    private static DecodeCore.Decoded tagged(int k) {
        Types.Frame f = Types.Frame.allocate(8, 8);
        f.r()[0] = k;
        return new DecodeCore.Decoded(f, new byte[TINY.blockCount()]);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void everyFrameIdenticalToSerialDecode() throws Exception {
        try (CmpFormat.Reader r = new CmpFormat.Reader(writeClip());
             DecodePipeline p = new DecodePipeline(r, 3, 4)) {
            for (int k = 0; k < FRAMES; k++) {
                assertSameAsSerial(r, k, p.get(k));
            }
        }
    }

    @Test
    void framesComeOutInOrderUnderRandomWorkerDelays() throws Exception {
        // Delays depend on k only, so the run is repeatable; early frames are often the slow ones.
        DecodePipeline.FrameSource slow = (k, s) -> {
            sleepQuietly(new Random(k * 31L).nextInt(12));
            return tagged(k);
        };
        try (DecodePipeline p = new DecodePipeline(TINY, 60, 4, 5, slow)) {
            for (int k = 0; k < 60; k++) {
                assertEquals(k, p.get(k).frame().r()[0], "got the wrong frame");
            }
        }
    }

    @Test
    void bufferNeverExceedsCapacity() throws Exception {
        try (DecodePipeline p = new DecodePipeline(TINY, 50, 4, 3, (k, s) -> tagged(k))) {
            sleepQuietly(50); // let fast workers race ahead before the consumer moves
            assertTrue(p.peakBuffered() <= 3, "prefetch overflowed: " + p.peakBuffered());
            for (int k = 0; k < 50; k++) {
                p.get(k);
                sleepQuietly(1);
            }
            assertTrue(p.peakBuffered() <= 3, "buffered " + p.peakBuffered() + " > capacity 3");
            assertEquals(3, p.peakBuffered(), "window should fill while the consumer is slow");
        }
    }

    @Test
    void seekBackAndJumpAheadReturnTheRightImage() throws Exception {
        try (CmpFormat.Reader r = new CmpFormat.Reader(writeClip());
             DecodePipeline p = new DecodePipeline(r, 2, 4)) {
            for (int k = 0; k <= 30; k++) {
                p.get(k);
            }
            for (int k : new int[] {29, 5, 0, 39, 12, 13, 14}) {
                assertSameAsSerial(r, k, p.get(k));
            }
        }
    }

    @Test
    void decodeErrorIsReportedForThatFrameOnly() throws Exception {
        DecodePipeline.FrameSource bad = (k, s) -> {
            if (k == 7) {
                throw new CmpFormat.CmpException("corrupt frame for the test");
            }
            return tagged(k);
        };
        try (DecodePipeline p = new DecodePipeline(TINY, 20, 2, 4, bad)) {
            assertEquals(6, p.get(6).frame().r()[0]);
            IOException e = assertThrows(IOException.class, () -> p.get(7));
            assertTrue(e.getMessage().contains("frame 7"), e.getMessage());
            assertEquals(8, p.get(8).frame().r()[0]);
        }
    }

    @Test
    void closeStopsEveryThread() throws Exception {
        DecodePipeline p = new DecodePipeline(TINY, 1000, 4, 3, (k, s) -> {
            sleepQuietly(2);
            return tagged(k);
        });
        p.get(0);
        p.close();
        assertEquals(0, p.liveThreads(), "decode threads left running");
        assertThrows(IllegalStateException.class, () -> p.get(1));
        p.close(); // second close is harmless
    }

    @Test
    void outOfRangeRejected() throws Exception {
        try (DecodePipeline p = new DecodePipeline(TINY, 5, 2, 2, (k, s) -> tagged(k))) {
            assertThrows(IndexOutOfBoundsException.class, () -> p.get(5));
            assertThrows(IndexOutOfBoundsException.class, () -> p.get(-1));
        }
    }

    @Test
    void defaultSizesAreSane() {
        assertTrue(DecodePipeline.defaultWorkers() >= 2);
        Header hd = new Header(0, 1920, 1080, 1920, 1088, 30, 1, 0, 2, 5, 0);
        int cap = DecodePipeline.defaultCapacity(hd, 6);
        assertTrue(cap >= 2 && cap <= 8, "1080p capacity " + cap);
        assertTrue(DecodePipeline.defaultCapacity(TINY, 12) == 12, "small frames: one slot per worker");
    }
}
