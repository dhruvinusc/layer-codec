package codec.decoder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import codec.common.Types;
import codec.common.VideoIO;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Side-by-side source (P3.4): frame k of the original, forward, from the ring, and after a reopen. */
class OriginalVideoTest {

    @TempDir
    static Path dir;
    static Path clip;
    /** Oracle: every frame read once, straight through, packed. */
    static List<int[]> truth;

    @BeforeAll
    static void makeClip() throws Exception {
        assumeTrue(ffmpegAvailable(), "ffmpeg not on PATH");
        clip = dir.resolve("o.mp4");
        Process p = new ProcessBuilder(ffmpeg(), "-v", "error", "-f", "lavfi",
                "-i", "testsrc=size=32x16:rate=30", "-frames:v", "50", "-c:v", "mpeg4", "-q:v", "2",
                "-pix_fmt", "yuv420p", clip.toString()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
        truth = new ArrayList<>();
        try (VideoIO.Reader r = VideoIO.open(clip)) {
            Types.Frame f = Types.Frame.allocate(32, 16);
            while (r.read(f)) {
                int[] px = new int[32 * 16];
                for (int i = 0; i < px.length; i++) {
                    px[i] = (f.r()[i] << 16) | (f.g()[i] << 8) | f.b()[i];
                }
                truth.add(px);
            }
        }
        assertEquals(50, truth.size());
    }

    @Test
    void forwardSkipRingHitAndReopenAllGiveTheSameFrameAsAStraightRead() throws Exception {
        try (OriginalVideo o = new OriginalVideo(clip, 32, 16)) {
            assertArrayEquals(truth.get(0), o.get(0));
            assertArrayEquals(truth.get(1), o.get(1));
            assertArrayEquals(truth.get(40), o.get(40), "jump ahead (the player dropped frames)");
            int[] again = o.get(39);
            assertArrayEquals(truth.get(39), again, "step back inside the ring");
            assertSame(again, o.get(39), "served from the ring, not reread");
            assertEquals(0, o.reopens());
            assertArrayEquals(truth.get(5), o.get(5), "step back past the ring");
            assertEquals(1, o.reopens(), "past the ring the clip is reopened once");
            assertArrayEquals(truth.get(6), o.get(6), "and then reads forward normally");
            assertEquals(1, o.reopens());
        }
    }

    @Test
    void framesPastTheEndOfTheMp4AreNull() throws Exception {
        try (OriginalVideo o = new OriginalVideo(clip, 32, 16)) {
            assertNull(o.get(60));
            assertArrayEquals(truth.get(49), o.get(49), "last frame still served after hitting the end");
        }
    }

    @Test
    void aDifferentVideoIsRejectedBeforeAnyWindowOpens() {
        IOException e = assertThrows(IOException.class, () -> new OriginalVideo(clip, 1920, 1080));
        assertTrue(e.getMessage().contains("not the same video"), e.getMessage());
    }

    @Test
    void theReaderThreadGetsAheadOfThePlayer() throws Exception {
        try (OriginalVideo o = new OriginalVideo(clip, 32, 16)) {
            o.get(0);
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!o.isReady(OriginalVideo.AHEAD) && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertTrue(o.isReady(OriginalVideo.AHEAD), "frames 1..AHEAD read without being asked");
            assertFalse(o.isReady(OriginalVideo.AHEAD + 2), "but not further than AHEAD");
            assertArrayEquals(truth.get(OriginalVideo.AHEAD), o.get(OriginalVideo.AHEAD));
        }
    }

    @Test
    void memoryStaysBoundedOverTheWholeClip() throws Exception {
        try (OriginalVideo o = new OriginalVideo(clip, 32, 16)) {
            for (int k = 0; k < 50; k++) {
                assertArrayEquals(truth.get(k), o.get(k), "frame " + k);
            }
            assertTrue(o.peakKept() <= OriginalVideo.BEHIND + OriginalVideo.AHEAD + 1,
                    "kept " + o.peakKept() + " frames");
        }
    }

    @Test
    void closeStopsTheReaderThread() throws Exception {
        OriginalVideo o = new OriginalVideo(clip, 32, 16);
        o.get(3);
        o.close();
        assertFalse(o.readerAlive(), "reader thread left running");
        assertThrows(IllegalStateException.class, () -> o.get(4));
        o.close();
    }

    private static String ffmpeg() {
        String v = System.getenv("FFMPEG");
        return v == null || v.isBlank() ? "ffmpeg" : v;
    }

    private static boolean ffmpegAvailable() {
        try {
            Process p = new ProcessBuilder(List.of(ffmpeg(), "-version")).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
