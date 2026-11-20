package codec.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import codec.common.CmpFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every way {@code mydecoder} can be given a bad command line or a bad file (P3.6): the exit code
 * and the one-line message. All of these fail before a window would open, so they run headless.
 */
class DecoderMainErrorsTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    /** A valid 3-frame .cmp, 30x10 (padded 32x16), zlib on. */
    private Path validCmp(String name) throws IOException {
        Path p = dir.resolve(name);
        CmpFormat.Header h = new CmpFormat.Header(CmpFormat.FLAG_ZLIB, 30, 10, 32, 16, 30, 1, 0, 2, 5, 0);
        try (CmpFormat.Writer w = new CmpFormat.Writer(p, h)) {
            for (int f = 0; f < 3; f++) {
                w.writeFrame(new byte[h.blockCount()], new short[h.blockCount() * CmpFormat.COEF_PER_BLOCK]);
            }
        }
        return p;
    }

    private static void poke(Path p, long pos, byte[] bytes) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
            f.seek(pos);
            f.write(bytes);
        }
    }

    @Test
    void noArgumentsPrintsUsageAndExits2() {
        assertEquals(2, DecoderMain.run(new String[] {}, err));
        assertTrue(err().contains("usage: mydecoder input_video.cmp"), err());
    }

    @Test
    void missingFileExits2() {
        assertEquals(2, DecoderMain.run(new String[] {dir.resolve("nope.cmp").toString()}, err));
        assertTrue(err().contains("file not found"), err());
    }

    @Test
    void nonNumberAfterWorkersExits2() throws IOException {
        Path cmp = validCmp("a.cmp");
        assertEquals(2, DecoderMain.run(new String[] {cmp.toString(), "--workers", "six"}, err));
        assertTrue(err().contains("usage:"), err());
    }

    @Test
    void anMp4OrOtherFileGivenAsTheCmpExits1() throws IOException {
        Path fake = dir.resolve("clip.cmp");
        Files.write(fake, new byte[200]);
        assertEquals(1, DecoderMain.run(new String[] {fake.toString()}, err));
        assertTrue(err().contains("not a .cmp file"), err());
    }

    @Test
    void fileShorterThanTheHeaderExits1() throws IOException {
        Path tiny = dir.resolve("tiny.cmp");
        Files.write(tiny, new byte[10]);
        assertEquals(1, DecoderMain.run(new String[] {tiny.toString()}, err));
        assertTrue(err().contains("shorter than the 48-byte header"), err());
    }

    @Test
    void wrongVersionExits1() throws IOException {
        Path cmp = validCmp("v.cmp");
        poke(cmp, 4, new byte[] {0, 9});
        assertEquals(1, DecoderMain.run(new String[] {cmp.toString()}, err));
        assertTrue(err().contains("unsupported .cmp version 9"), err());
    }

    @Test
    void unfinishedFileExits1() throws IOException {
        Path cmp = validCmp("u.cmp");
        poke(cmp, CmpFormat.FOOTER_OFFSET_POS, new byte[8]); // footer offset 0: encoder never closed it
        assertEquals(1, DecoderMain.run(new String[] {cmp.toString()}, err));
        assertTrue(err().contains("unfinished .cmp file"), err());
    }

    @Test
    void truncatedFileExits1() throws IOException {
        Path cmp = validCmp("t.cmp");
        try (RandomAccessFile f = new RandomAccessFile(cmp.toFile(), "rw")) {
            f.setLength(f.length() - 5);
        }
        assertEquals(1, DecoderMain.run(new String[] {cmp.toString()}, err));
        assertTrue(err().contains("truncated or corrupt"), err());
    }

    @Test
    void benchOnABadFileExits1AndOnAGoodFileExits0() throws IOException {
        Path bad = dir.resolve("bad.cmp");
        Files.write(bad, new byte[100]);
        assertEquals(1, DecoderMain.run(new String[] {bad.toString(), "--bench"}, err));
        assertEquals(0, DecoderMain.run(new String[] {validCmp("g.cmp").toString(), "--bench", "--workers", "2"}, err));
    }

    @Test
    void originalThatIsADifferentVideoExits1BeforeAnyWindow() throws Exception {
        assumeTrue(ffmpegAvailable(), "ffmpeg not on PATH");
        Path mp4 = dir.resolve("other.mp4");
        Process p = new ProcessBuilder(ffmpeg(), "-v", "error", "-f", "lavfi", "-i", "testsrc=size=64x32:rate=30",
                "-frames:v", "3", "-c:v", "mpeg4", "-pix_fmt", "yuv420p", mp4.toString())
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
        assertEquals(1, DecoderMain.run(new String[] {validCmp("o.cmp").toString(), mp4.toString()}, err));
        assertTrue(err().contains("not the same video"), err());
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
