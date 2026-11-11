package codec.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codec.common.CmpFormat.CmpException;
import codec.common.CmpFormat.Header;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CmpFormatTest {

    @TempDir
    Path dir;

    /** True 30x10, padded 32x16: 4 x 2 = 8 blocks of 8x8. */
    private static Header header(boolean zlib) {
        return new Header(zlib ? CmpFormat.FLAG_ZLIB : 0, 30, 10, 32, 16, 30000, 1001, 0, 2, 5, 0);
    }

    private record Frame(byte[] types, short[] coef) {
    }

    private static Frame randomFrame(Random rnd, Header h) {
        byte[] t = new byte[h.blockCount()];
        short[] c = new short[h.blockCount() * CmpFormat.COEF_PER_BLOCK];
        for (int i = 0; i < t.length; i++) {
            t[i] = (byte) rnd.nextInt(2);
        }
        for (int i = 0; i < c.length; i++) {
            c[i] = (short) (rnd.nextInt(2049) - 1024); // includes negatives, int16 range of real data
        }
        return new Frame(t, c);
    }

    private Path write(String name, Header h, Frame... frames) throws IOException {
        Path p = dir.resolve(name);
        try (CmpFormat.Writer w = new CmpFormat.Writer(p, h)) {
            for (Frame f : frames) {
                w.writeFrame(f.types(), f.coef());
            }
        }
        return p;
    }

    private Path patched(Path src, int pos, byte... bytes) throws IOException {
        byte[] all = Files.readAllBytes(src);
        System.arraycopy(bytes, 0, all, pos, bytes.length);
        Path p = dir.resolve("patched_" + pos + "_" + src.getFileName());
        Files.write(p, all);
        return p;
    }

    private static String errorOf(Path p) {
        CmpException e = assertThrows(CmpException.class, () -> {
            try (CmpFormat.Reader r = new CmpFormat.Reader(p)) {
                byte[] t = new byte[r.header().blockCount()];
                short[] c = new short[t.length * CmpFormat.COEF_PER_BLOCK];
                for (int k = 0; k < r.frameCount(); k++) {
                    r.readFrame(k, t, c);
                }
            }
        });
        return e.getMessage();
    }

    // ---- layout ---------------------------------------------------------------------------

    @Test
    void headerIs48BytesWithPatchedCountsAndZeroReserved() throws IOException {
        Random rnd = new Random(1);
        Header h = header(true);
        Path p = write("a.cmp", h, randomFrame(rnd, h), randomFrame(rnd, h));
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(p));
        assertEquals(CmpFormat.MAGIC, b.getInt(0));
        assertEquals(1, b.getShort(4));
        assertEquals(CmpFormat.FLAG_ZLIB, b.getShort(6));
        assertEquals(30, b.getInt(8));
        assertEquals(10, b.getInt(12));
        assertEquals(32, b.getInt(16));
        assertEquals(16, b.getInt(20));
        assertEquals(30000, b.getInt(24));
        assertEquals(1001, b.getInt(28));
        assertEquals(2, b.getInt(32));      // frame count patched at close
        assertEquals(2, b.get(36));
        assertEquals(5, b.get(37));
        assertEquals(0, b.getShort(38));    // reserved written as 0
        long footer = b.getLong(40);
        assertEquals(Files.size(p), footer + 2 * 8);
        assertEquals(48, b.getLong((int) footer)); // first frame starts right after the header
    }

    @Test
    void threeFramesRoundTripIdenticallyWithAndWithoutZlib() throws IOException {
        Random rnd = new Random(2);
        Header hz = header(true);
        Frame[] fr = {randomFrame(rnd, hz), randomFrame(rnd, hz), randomFrame(rnd, hz)};
        Path z = write("z.cmp", hz, fr);
        Path raw = write("r.cmp", header(false), fr);
        for (Path p : new Path[] {z, raw}) {
            try (CmpFormat.Reader r = new CmpFormat.Reader(p)) {
                assertEquals(3, r.frameCount());
                byte[] t = new byte[8];
                short[] c = new short[8 * CmpFormat.COEF_PER_BLOCK];
                for (int k : new int[] {2, 0, 1}) { // random access, out of order
                    r.readFrame(k, t, c);
                    assertArrayEquals(fr[k].types(), t);
                    assertArrayEquals(fr[k].coef(), c);
                }
            }
        }
    }

    @Test
    void blocksAreInScanLineOrder385BytesEach() throws IOException {
        Header h = header(false);
        byte[] t = new byte[8];
        short[] c = new short[8 * CmpFormat.COEF_PER_BLOCK];
        for (int b = 0; b < 8; b++) {
            t[b] = (byte) (b % 2);
            c[b * CmpFormat.COEF_PER_BLOCK] = (short) (100 + b);        // first R coefficient
            c[b * CmpFormat.COEF_PER_BLOCK + 64] = (short) (-200 - b);  // first G coefficient
            c[b * CmpFormat.COEF_PER_BLOCK + 128] = (short) (300 + b);  // first B coefficient
        }
        ByteBuffer raw = ByteBuffer.wrap(CmpFormat.encodeBaseline(h, t, c));
        assertEquals(8 * 385, raw.capacity());
        for (int b = 0; b < 8; b++) {
            int base = b * 385;
            assertEquals(b % 2, raw.get(base));
            assertEquals(100 + b, raw.getShort(base + 1));
            assertEquals(-200 - b, raw.getShort(base + 1 + 128));
            assertEquals(300 + b, raw.getShort(base + 1 + 256));
        }
    }

    @Test
    void blockTypeOf8x8IsItsMacroblockLabel() {
        // 2 x 1 macroblocks: left background, right foreground region 7. Blocks: 4 x 2.
        Types.Labels l = new Types.Labels(2, 1, new byte[] {0, 7});
        byte[] t = CmpFormat.blockTypes(l, 4, 2);
        assertArrayEquals(new byte[] {0, 0, 1, 1, 0, 0, 1, 1}, t);
        Types.Labels region255 = new Types.Labels(1, 1, new byte[] {(byte) 255});
        assertArrayEquals(new byte[] {1, 1, 1, 1}, CmpFormat.blockTypes(region255, 2, 2));
    }

    @Test
    void uncompressedFileStoresEqualLengths() throws IOException {
        Path p = write("eq.cmp", header(false), randomFrame(new Random(3), header(false)));
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(p));
        assertEquals(8 * 385, b.getInt(48));
        assertEquals(8 * 385, b.getInt(52));
    }

    // ---- validation rules (03 D7), one test each --------------------------------------------

    @Test
    void ruleMagicRejected() throws IOException {
        Path p = patched(write("m.cmp", header(true), randomFrame(new Random(4), header(true))), 0, (byte) 'X');
        assertTrue(errorOf(p).contains("magic"));
    }

    @Test
    void ruleUnknownVersionRejected() throws IOException {
        Path p = patched(write("v.cmp", header(true), randomFrame(new Random(5), header(true))), 4, (byte) 0, (byte) 2);
        assertTrue(errorOf(p).contains("version 2"));
    }

    @Test
    void ruleUnknownFlagBitsRejected() throws IOException {
        Path p = patched(write("f.cmp", header(true), randomFrame(new Random(6), header(true))), 6, (byte) 0, (byte) 0x11);
        assertTrue(errorOf(p).contains("unknown flag bits"));
    }

    @Test
    void ruleUnknownModeRejected() throws IOException {
        Path p = patched(write("md.cmp", header(true), randomFrame(new Random(7), header(true))), 6, (byte) 0, (byte) 0x03);
        assertTrue(errorOf(p).contains("unknown mode 1"));
    }

    @Test
    void ruleN1OutOfRangeRejected() throws IOException {
        Path p = patched(write("n1.cmp", header(true), randomFrame(new Random(8), header(true))), 36, (byte) 11);
        assertTrue(errorOf(p).contains("n1 = 11"));
    }

    @Test
    void ruleN2ReadUnsignedAndRejected() throws IOException {
        // 0xFF must read as 255, not -1 (signed byte trap).
        Path p = patched(write("n2.cmp", header(true), randomFrame(new Random(9), header(true))), 37, (byte) 0xFF);
        assertTrue(errorOf(p).contains("n2 = 255"));
    }

    @Test
    void ruleBlockTypeMustBe0Or1() throws IOException {
        Header h = header(false);
        Path p = write("bt.cmp", h, randomFrame(new Random(10), h));
        int blockThree = 48 + 8 + 3 * 385;
        assertTrue(errorOf(patched(p, blockThree, (byte) 2)).contains("block 3 has block_type 2"));
        assertTrue(errorOf(patched(p, blockThree, (byte) 0xFF)).contains("block_type 255"));
    }

    @Test
    void ruleFooterOffsetZeroMeansUnfinished() throws IOException {
        Path p = patched(write("u.cmp", header(true), randomFrame(new Random(11), header(true))), 40, new byte[8]);
        assertTrue(errorOf(p).contains("unfinished"));
    }

    @Test
    void ruleZlibOffRequiresEqualLengths() throws IOException {
        Header h = header(false);
        Path p = write("len.cmp", h, randomFrame(new Random(12), h));
        Path bad = patched(p, 52, ByteBuffer.allocate(4).putInt(8 * 385 + 1).array()); // raw length field
        assertTrue(errorOf(bad).contains("zlib is off"));
    }

    @Test
    void truncatedFileRejected() throws IOException {
        Path p = write("t.cmp", header(true), randomFrame(new Random(13), header(true)));
        byte[] all = Files.readAllBytes(p);
        Path cut = dir.resolve("cut.cmp");
        Files.write(cut, Arrays.copyOf(all, all.length - 10));
        assertTrue(errorOf(cut).contains("truncated"));
        Path tiny = dir.resolve("tiny.cmp");
        Files.write(tiny, Arrays.copyOf(all, 20));
        assertTrue(errorOf(tiny).contains("shorter than the 48-byte header"));
    }

    @Test
    void corruptZlibPayloadRejected() throws IOException {
        Path p = write("cz.cmp", header(true), randomFrame(new Random(14), header(true)));
        Path bad = patched(p, 48 + 8 + 2, (byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0x55);
        assertTrue(errorOf(bad).startsWith("frame 0"));
    }

    @Test
    void writerRejectsBadExponentAndBadBlockType() {
        Header badN = new Header(0, 30, 10, 32, 16, 30, 1, 0, 2, 11, 0);
        assertThrows(CmpException.class, () -> new CmpFormat.Writer(dir.resolve("x.cmp"), badN));
        Header h = header(false);
        byte[] t = new byte[8];
        t[5] = 3;
        short[] c = new short[8 * CmpFormat.COEF_PER_BLOCK];
        assertThrows(CmpException.class, () -> CmpFormat.encodeBaseline(h, t, c));
    }
}
