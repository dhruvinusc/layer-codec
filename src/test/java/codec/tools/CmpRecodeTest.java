package codec.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codec.common.CmpFormat;
import codec.common.CmpFormat.Header;
import codec.common.RunLength;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** P4-B tool: baseline -> mode 1 keeps every coefficient and header field, and shrinks the file. */
class CmpRecodeTest {

    @TempDir
    Path dir;

    @Test
    void recodeKeepsCoefficientsHeaderAndFrameCount() throws Exception {
        Header h = new Header(CmpFormat.FLAG_ZLIB, 30, 10, 32, 16, 30000, 1001, 0, 1, 7, 0);
        Path in = dir.resolve("in.cmp");
        Random r = new Random(3);
        byte[][] types = new byte[5][];
        short[][] coefs = new short[5][];
        try (CmpFormat.Writer w = new CmpFormat.Writer(in, h)) {
            for (int f = 0; f < 5; f++) {
                types[f] = new byte[h.blockCount()];
                coefs[f] = new short[h.blockCount() * CmpFormat.COEF_PER_BLOCK];
                for (int i = 0; i < coefs[f].length; i += 9) {
                    coefs[f][i] = (short) (r.nextInt(201) - 100);
                }
                types[f][f % h.blockCount()] = 1;
                w.writeFrame(types[f], coefs[f]);
            }
        }
        Path out = dir.resolve("out.cmp");
        String summary = CmpRecode.recode(in, out);
        assertTrue(summary.contains("frames 5"), summary);
        try (CmpFormat.Reader r1 = new CmpFormat.Reader(out)) {
            Header h1 = r1.header();
            assertEquals(CmpFormat.MODE_RLE, h1.mode());
            assertEquals(5, r1.frameCount());
            assertEquals(30000, h1.fpsNum());
            assertEquals(1001, h1.fpsDen());
            assertEquals(1, h1.n1());
            assertEquals(7, h1.n2());
            for (int f = 0; f < 5; f++) {
                byte[] t = new byte[h.blockCount()];
                short[] c = new short[h.blockCount() * CmpFormat.COEF_PER_BLOCK];
                RunLength.decode(h1, r1.payload(f), t, c);
                assertArrayEquals(types[f], t);
                assertArrayEquals(coefs[f], c);
            }
        }
        assertTrue(Files.size(out) < Files.size(in));
    }

    @Test
    void recodingAMode1FileIsRefused() throws Exception {
        Header h = new Header(CmpFormat.FLAG_ZLIB, 30, 10, 32, 16, 30, 1, 0, 1, 7, 0);
        Path in = dir.resolve("a.cmp");
        try (CmpFormat.Writer w = new CmpFormat.Writer(in, h)) {
            w.writeFrame(new byte[h.blockCount()], new short[h.blockCount() * CmpFormat.COEF_PER_BLOCK]);
        }
        Path once = dir.resolve("b.cmp");
        CmpRecode.recode(in, once);
        IOException e = assertThrows(IOException.class, () -> CmpRecode.recode(once, dir.resolve("c.cmp")));
        assertTrue(e.getMessage().contains("expected 0"), e.getMessage());
    }
}
