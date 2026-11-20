package codec.decoder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codec.common.CmpFormat;
import codec.common.CmpFormat.Header;
import codec.common.RunLength;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** P4-B: mode-1 files decode to exactly the same pixels as baseline files, through the same player path. */
class FrameDecodeTest {

    @TempDir
    Path dir;

    private static final Header H0 = new Header(CmpFormat.FLAG_ZLIB, 30, 10, 32, 16, 30, 1, 0, 2, 5, 0);
    private static final Header H1 = new Header(CmpFormat.FLAG_ZLIB | (CmpFormat.MODE_RLE << CmpFormat.MODE_SHIFT),
            30, 10, 32, 16, 30, 1, 0, 2, 5, 0);
    private static final int FRAMES = 12;

    /** Writes the same random sparse frames twice: once as baseline, once as mode 1. */
    private void writeBoth(Path p0, Path p1) throws Exception {
        Random r = new Random(11);
        try (CmpFormat.Writer w0 = new CmpFormat.Writer(p0, H0); CmpFormat.Writer w1 = new CmpFormat.Writer(p1, H1)) {
            for (int f = 0; f < FRAMES; f++) {
                byte[] t = new byte[H0.blockCount()];
                short[] c = new short[H0.blockCount() * CmpFormat.COEF_PER_BLOCK];
                for (int b = 0; b < t.length; b++) {
                    t[b] = (byte) r.nextInt(2);
                }
                for (int i = 0; i < c.length; i++) {
                    c[i] = r.nextDouble() < 0.15 ? (short) (r.nextInt(81) - 40) : 0;
                }
                w0.writeFrame(t, c);
                w1.writePayload(RunLength.encode(H1, t, c));
            }
        }
    }

    @Test
    void mode1DecodesToTheSamePixelsAsBaselineThroughThePipeline() throws Exception {
        Path p0 = dir.resolve("b.cmp");
        Path p1 = dir.resolve("r.cmp");
        writeBoth(p0, p1);
        try (CmpFormat.Reader r0 = new CmpFormat.Reader(p0); CmpFormat.Reader r1 = new CmpFormat.Reader(p1);
             DecodePipeline q0 = new DecodePipeline(r0, 2, 4); DecodePipeline q1 = new DecodePipeline(r1, 3, 4)) {
            assertEquals(CmpFormat.MODE_RLE, r1.header().mode());
            for (int k = 0; k < FRAMES; k++) {
                DecodeCore.Decoded a = q0.get(k);
                DecodeCore.Decoded b = q1.get(k);
                assertArrayEquals(a.frame().r(), b.frame().r(), "R frame " + k);
                assertArrayEquals(a.frame().g(), b.frame().g(), "G frame " + k);
                assertArrayEquals(a.frame().b(), b.frame().b(), "B frame " + k);
                assertArrayEquals(a.types(), b.types(), "types frame " + k);
            }
        }
        assertTrue(Files.size(p1) < Files.size(p0), "mode 1 is smaller on sparse frames");
    }

    @Test
    void benchPlaysMode1WithTheSameCommandLine() throws Exception {
        Path p0 = dir.resolve("b2.cmp");
        Path p1 = dir.resolve("r2.cmp");
        writeBoth(p0, p1);
        PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertEquals(0, DecoderMain.run(new String[] {p1.toString(), "--bench", "--workers", "2"}, err));
    }
}
