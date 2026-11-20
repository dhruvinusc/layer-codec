package codec.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import codec.common.Types;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodecHarnessTest {

    @Test
    void checkerboardLabelsAlternateByMacroblock() {
        Types.Labels l = CodecHarness.fakeLabels("checker", 3, 2);
        assertArrayEquals(new byte[] {1, 0, 1, 0, 1, 0}, l.id());
        assertEquals(6, sum(CodecHarness.fakeLabels("all-fg", 3, 2).id()));
        assertEquals(0, sum(CodecHarness.fakeLabels("all-bg", 3, 2).id()));
    }

    private static int sum(byte[] b) {
        int s = 0;
        for (byte x : b) {
            s += x;
        }
        return s;
    }

    @Test
    void badArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CodecHarness.parse(new String[] {"a.mp4", "1"}));
        assertThrows(IllegalArgumentException.class,
                () -> CodecHarness.parse(new String[] {"a.mp4", "1", "2", "--labels", "stripes"}));
    }

    @Test
    void syntheticClipEndToEndIsDeterministicAndN0IsNearlyLossless(@TempDir Path dir) throws Exception {
        assumeTrue(ffmpegAvailable(), "ffmpeg not on PATH");
        // 72x40 is not a multiple of 16, so padding and crop are exercised.
        Path clip = dir.resolve("t.mp4");
        Process p = new ProcessBuilder(ffmpeg(), "-v", "error", "-f", "lavfi",
                "-i", "testsrc=size=72x40:rate=30", "-frames:v", "6", "-c:v", "mpeg4", "-q:v", "2",
                "-pix_fmt", "yuv420p", clip.toString()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());

        CodecHarness.Args zero = CodecHarness.parse(new String[] {clip.toString(), "0", "0", "--threads", "3"});
        String r1 = CodecHarness.run(zero, dir.resolve("o1"));
        String r2 = CodecHarness.run(zero, dir.resolve("o2"));
        assertEquals(hash(r1), hash(r2), "same input and args must give the same file");
        assertTrue(meanAll(r1) >= 45, r1);

        String layered = CodecHarness.run(CodecHarness.parse(new String[] {clip.toString(), "1", "7"}), dir.resolve("o3"));
        String swapped = CodecHarness.run(CodecHarness.parse(new String[] {clip.toString(), "7", "1"}), dir.resolve("o4"));
        assertTrue(fg(layered) > bg(layered) + 5, layered);
        assertTrue(bg(swapped) > fg(swapped) + 5, swapped);
        assertTrue(Files.list(dir.resolve("o3/evidence")).count() == 2);
    }

    private static String hash(String report) {
        return report.lines().filter(l -> l.startsWith("sha256")).findFirst().orElseThrow();
    }

    private static double field(String report, String key) {
        String line = report.lines().filter(l -> l.startsWith("frames")).findFirst().orElseThrow();
        String after = line.substring(line.indexOf("mean PSNR"));
        String[] parts = after.split("\\s+");
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].equals(key)) {
                return Double.parseDouble(parts[i + 1]);
            }
        }
        throw new IllegalStateException(key + " not in " + line);
    }

    private static double meanAll(String r) {
        return field(r, "all");
    }

    private static double fg(String r) {
        return field(r, "fg");
    }

    private static double bg(String r) {
        return field(r, "bg");
    }

    /** Same rule as VideoIO (frozen, package-private): FFMPEG env override, else PATH. */
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
