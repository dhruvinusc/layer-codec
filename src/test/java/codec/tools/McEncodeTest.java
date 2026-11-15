package codec.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import codec.common.CmpFormat;
import codec.common.McCodec;
import codec.common.Types;
import codec.common.VideoIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** P4-A end to end: mp4 -> mode-2 .cmp -> every frame decodes, close to the original, deterministic. */
class McEncodeTest {

    @TempDir
    Path dir;

    private Path clip() throws Exception {
        assumeTrue(ffmpegAvailable(), "ffmpeg not on PATH");
        Path clip = dir.resolve("t.mp4");
        // 72x40 (not a multiple of 16) with a moving test pattern, 20 frames = 2.5 groups.
        Process p = new ProcessBuilder(ffmpeg(), "-v", "error", "-f", "lavfi", "-i", "testsrc=size=72x40:rate=30",
                "-frames:v", "20", "-c:v", "mpeg4", "-q:v", "2", "-pix_fmt", "yuv420p", clip.toString())
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
        return clip;
    }

    private static double psnr(Types.Frame a, Types.Frame b) {
        double se = 0;
        int[][] pa = {a.r(), a.g(), a.b()};
        int[][] pb = {b.r(), b.g(), b.b()};
        for (int ch = 0; ch < 3; ch++) {
            for (int i = 0; i < pa[ch].length; i++) {
                double d = pa[ch][i] - pb[ch][i];
                se += d * d;
            }
        }
        double mse = se / (3.0 * a.r().length);
        return mse == 0 ? 99 : 10 * Math.log10(255.0 * 255 / mse);
    }

    @Test
    void everyFrameDecodesCloseToTheOriginalAndRunsAreIdentical() throws Exception {
        Path clip = clip();
        Path o1 = dir.resolve("a.cmp");
        Path o2 = dir.resolve("b.cmp");
        String summary = McEncode.encode(McEncode.parse(new String[] {clip.toString(), "0", "0", o1.toString()}));
        assertTrue(summary.contains("frames 20"), summary);
        McEncode.encode(McEncode.parse(new String[] {clip.toString(), "0", "0", o2.toString()}));
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        assertArrayEquals(md.digest(Files.readAllBytes(o1)), md.digest(Files.readAllBytes(o2)), "deterministic");

        try (CmpFormat.Reader r = new CmpFormat.Reader(o1); VideoIO.Reader v = VideoIO.open(clip)) {
            assertEquals(CmpFormat.MODE_MC, r.header().mode());
            assertEquals(20, r.frameCount());
            McCodec.State st = new McCodec.State(r.header().paddedW(), r.header().paddedH());
            byte[] t = new byte[r.header().blockCount()];
            short[] c = new short[r.header().blockCount() * CmpFormat.COEF_PER_BLOCK];
            Types.Frame orig = Types.Frame.allocate(72, 40);
            for (int k = 0; k < 20; k++) {
                McCodec.decode(r.header(), st, r.payload(k), k, t, c);
                assertTrue(v.read(orig));
                double q = psnr(orig, st.frame(72, 40));
                assertTrue(q > 40, "frame " + k + " only " + q + " dB at n = 0 (drift or bad prediction)");
            }
        }
    }

    @Test
    void defaultOutputNameAndFrameLimit() throws Exception {
        Path clip = clip();
        McEncode.Args a = McEncode.parse(new String[] {clip.toString(), "2", "5", "--frames", "9"});
        assertEquals(dir.resolve("t_mc.cmp"), a.output());
        McEncode.encode(a);
        try (CmpFormat.Reader r = new CmpFormat.Reader(a.output())) {
            assertEquals(9, r.frameCount());
        }
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
