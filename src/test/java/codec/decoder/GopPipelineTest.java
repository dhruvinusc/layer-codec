package codec.decoder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codec.common.CmpFormat;
import codec.common.CmpFormat.Header;
import codec.common.McCodec;
import codec.common.Types;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** P4-A decoder: whole groups decoded in parallel give exactly the in-order result. */
class GopPipelineTest {

    @TempDir
    Path dir;

    static final Header H = new Header(CmpFormat.FLAG_ZLIB | (CmpFormat.MODE_MC << CmpFormat.MODE_SHIFT),
            60, 40, 64, 48, 30, 1, 0, 2, 5, 0);
    static final int FRAMES = 35; // 4 full groups + 3 frames

    static Types.Frame frame(int t) {
        Types.Frame f = Types.Frame.allocate(64, 48);
        for (int y = 0; y < 48; y++) {
            for (int x = 0; x < 64; x++) {
                int i = y * 64 + x;
                f.r()[i] = (int) (128 + 90 * Math.sin((x - 2 * t) * 0.21) * Math.cos((y - t) * 0.17));
                f.g()[i] = (x * 3 + y * 5 + t * 7) & 0xFF;
                f.b()[i] = (int) (128 + 70 * Math.cos((x + y - 3 * t) * 0.1));
            }
        }
        return f;
    }

    static Types.MotionField motion() {
        short[] dx = new short[12];
        short[] dy = new short[12];
        java.util.Arrays.fill(dx, (short) -2);
        java.util.Arrays.fill(dy, (short) -1);
        return new Types.MotionField(4, 3, dx, dy);
    }

    /** Writes a mode-2 file; returns the payloads too. */
    List<byte[]> write(Path p) throws Exception {
        McCodec.State st = new McCodec.State(64, 48);
        Random r = new Random(8);
        List<byte[]> payloads = new ArrayList<>();
        try (CmpFormat.Writer w = new CmpFormat.Writer(p, H)) {
            for (int k = 0; k < FRAMES; k++) {
                byte[] t = new byte[H.blockCount()];
                for (int b = 0; b < t.length; b++) {
                    t[b] = (byte) r.nextInt(2);
                }
                byte[] pl = McCodec.encode(H, st, frame(k), t, motion(), k);
                payloads.add(pl);
                w.writePayload(pl);
            }
        }
        return payloads;
    }

    /** Oracle: decode every frame in order with one reference. */
    static List<DecodeCore.Decoded> sequential(List<byte[]> payloads) throws Exception {
        McCodec.State st = new McCodec.State(64, 48);
        byte[] t = new byte[H.blockCount()];
        short[] c = new short[H.blockCount() * CmpFormat.COEF_PER_BLOCK];
        List<DecodeCore.Decoded> out = new ArrayList<>();
        for (int k = 0; k < payloads.size(); k++) {
            McCodec.decode(H, st, payloads.get(k), k, t, c);
            out.add(new DecodeCore.Decoded(st.frame(60, 40), t.clone()));
        }
        return out;
    }

    static void same(DecodeCore.Decoded want, DecodeCore.Decoded got, int k) {
        assertArrayEquals(want.frame().r(), got.frame().r(), "R frame " + k);
        assertArrayEquals(want.frame().g(), got.frame().g(), "G frame " + k);
        assertArrayEquals(want.frame().b(), got.frame().b(), "B frame " + k);
        assertArrayEquals(want.types(), got.types(), "types frame " + k);
    }

    @Test
    void everyFrameIdenticalToInOrderDecoding() throws Exception {
        Path p = dir.resolve("m.cmp");
        List<DecodeCore.Decoded> want = sequential(write(p));
        try (CmpFormat.Reader r = new CmpFormat.Reader(p); GopPipeline g = new GopPipeline(r, 3, 24)) {
            for (int k = 0; k < FRAMES; k++) {
                same(want.get(k), g.get(k), k);
            }
            assertTrue(g.peakBuffered() <= 24, "buffered " + g.peakBuffered());
        }
    }

    @Test
    void orderHoldsUnderRandomDelays() throws Exception {
        List<byte[]> pl = write(dir.resolve("d.cmp"));
        List<DecodeCore.Decoded> want = sequential(pl);
        GopPipeline.PayloadSource slow = k -> {
            try {
                Thread.sleep(new Random(k * 17L).nextInt(6));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return pl.get(k);
        };
        try (GopPipeline g = new GopPipeline(H, FRAMES, 4, 32, slow)) {
            for (int k = 0; k < FRAMES; k++) {
                same(want.get(k), g.get(k), k);
            }
        }
    }

    @Test
    void seekingIntoTheMiddleOfAGroupDecodesFromItsIFrame() throws Exception {
        Path p = dir.resolve("s.cmp");
        List<DecodeCore.Decoded> want = sequential(write(p));
        try (CmpFormat.Reader r = new CmpFormat.Reader(p); GopPipeline g = new GopPipeline(r, 2, 16)) {
            for (int k = 0; k <= 30; k++) {
                g.get(k);
            }
            for (int k : new int[] {13, 5, 0, 34, 17, 18, 9}) {
                same(want.get(k), g.get(k), k);
            }
        }
    }

    @Test
    void aBadFrameSpoilsOnlyTheRestOfItsGroup() throws Exception {
        List<byte[]> pl = write(dir.resolve("e.cmp"));
        List<DecodeCore.Decoded> want = sequential(pl);
        GopPipeline.PayloadSource bad = k -> {
            if (k == 10) {
                throw new CmpFormat.CmpException("corrupt frame for the test");
            }
            return pl.get(k);
        };
        try (GopPipeline g = new GopPipeline(H, FRAMES, 2, 16, bad)) {
            same(want.get(9), g.get(9), 9);
            assertTrue(assertThrows(IOException.class, () -> g.get(10)).getMessage().contains("frame 10"));
            assertThrows(IOException.class, () -> g.get(11));
            same(want.get(16), g.get(16), 16);
        }
    }

    @Test
    void closeStopsEveryThread() throws Exception {
        List<byte[]> pl = write(dir.resolve("c.cmp"));
        GopPipeline g = new GopPipeline(H, FRAMES, 3, 24, pl::get);
        g.get(0);
        g.close();
        assertEquals(0, g.liveThreads());
        assertThrows(IllegalStateException.class, () -> g.get(1));
    }

    @Test
    void benchAndCapacityWorkForMode2() throws Exception {
        Path p = dir.resolve("b.cmp");
        write(p);
        PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertEquals(0, DecoderMain.run(new String[] {p.toString(), "--bench", "--workers", "1,3"}, err));
        Header hd = new Header(H.flags(), 1920, 1080, 1920, 1088, 30, 1, 0, 2, 5, 0);
        int cap = GopPipeline.defaultCapacity(hd, 4);
        assertTrue(cap >= McCodec.GOP && cap <= 4 * McCodec.GOP, "1080p capacity " + cap);
        assertThrows(IllegalArgumentException.class, () -> new GopPipeline(H, 5, 1, 4, k -> null));
    }
}
