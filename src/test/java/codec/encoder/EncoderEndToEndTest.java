package codec.encoder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import codec.common.CmpFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** W1: the real encoder on a small synthetic clip (needs ffmpeg, skipped otherwise). */
class EncoderEndToEndTest {

    @TempDir
    Path dir;

    private Path clip(String name) throws Exception {
        assumeTrue(ffmpegAvailable(), "ffmpeg not on PATH");
        Path mp4 = dir.resolve(name);
        // 72x40 is not a multiple of 16 in either direction: padding and cropping are exercised.
        Process p = new ProcessBuilder(ffmpeg(), "-v", "error", "-f", "lavfi",
                "-i", "testsrc=size=72x40:rate=30", "-frames:v", "6", "-c:v", "mpeg4", "-q:v", "2",
                mp4.toString()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assumeTrue(p.waitFor() == 0 && Files.size(mp4) > 0, "ffmpeg could not make the test clip");
        return mp4;
    }

    @Test
    void encodesEveryFrameWithTheHeaderTheDecoderNeeds() throws Exception {
        Path mp4 = clip("input_video.mp4");
        EncoderMain.Args a = EncoderMain.parse(new String[] {mp4.toString(), "2", "5"});
        String summary = EncoderMain.encode(a);
        assertTrue(summary.contains("frames 6"), summary);
        try (CmpFormat.Reader r = new CmpFormat.Reader(dir.resolve("input_video.cmp"))) {
            CmpFormat.Header h = r.header();
            assertEquals(6, r.frameCount());
            assertEquals(2, h.n1());
            assertEquals(5, h.n2());
            assertEquals(72, h.width());
            assertEquals(40, h.height());
            assertEquals(80, h.paddedW());
            assertEquals(48, h.paddedH());
            assertEquals(CmpFormat.MODE_BASELINE, h.mode());
        }
    }

    @Test
    void sameInputGivesTheSameFileAndRleModeIsMarked() throws Exception {
        Path mp4 = clip("a.mp4");
        EncoderMain.encode(EncoderMain.parse(new String[] {mp4.toString(), "1", "6"}));
        byte[] first = Files.readAllBytes(dir.resolve("a.cmp"));
        EncoderMain.encode(EncoderMain.parse(new String[] {mp4.toString(), "1", "6"}));
        assertArrayEquals(first, Files.readAllBytes(dir.resolve("a.cmp")));
        EncoderMain.encode(EncoderMain.parse(new String[] {mp4.toString(), "1", "6", "--mode", "rle"}));
        try (CmpFormat.Reader r = new CmpFormat.Reader(dir.resolve("a.cmp"))) {
            assertEquals(CmpFormat.MODE_RLE, r.header().mode());
            assertEquals(6, r.frameCount());
        }
        assertFalse(java.util.Arrays.equals(first, Files.readAllBytes(dir.resolve("a.cmp"))));
    }

    @Test
    void modeFlagIsOptionalAndValidated() throws IOException {
        Path in = Files.createFile(dir.resolve("x.mp4"));
        assertFalse(EncoderMain.parse(new String[] {in.toString(), "2", "5"}).rle());
        assertTrue(EncoderMain.parse(new String[] {in.toString(), "2", "5", "--mode", "rle"}).rle());
        assertFalse(EncoderMain.parse(new String[] {in.toString(), "2", "5", "--mode", "baseline"}).rle());
        assertThrows(IllegalArgumentException.class,
                () -> EncoderMain.parse(new String[] {in.toString(), "2", "5", "--mode", "zip"}));
        assertThrows(IllegalArgumentException.class,
                () -> EncoderMain.parse(new String[] {in.toString(), "2", "5", "--mode"}));
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
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
