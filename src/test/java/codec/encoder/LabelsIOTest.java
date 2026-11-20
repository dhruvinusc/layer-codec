package codec.encoder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codec.common.Types.Labels;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LabelsIOTest {

    @TempDir
    Path dir;

    private static Labels frame(int seed) {
        byte[] id = new byte[4 * 3];
        for (int i = 0; i < id.length; i++) {
            id[i] = (byte) ((i * 7 + seed) % 5 == 0 ? (seed % 255) + 1 : 0);
        }
        id[11] = (byte) 200;   // region ids above 127 must survive (unsigned)
        return new Labels(4, 3, id);
    }

    private String writeThree(Path p) throws IOException {
        try (LabelsIO.Writer w = new LabelsIO.Writer(p, 4, 3)) {
            for (int k = 0; k < 3; k++) {
                w.write(frame(k));
            }
            return w.hash();
        }
    }

    @Test
    void roundTripRandomAccessAndUnsignedIds() throws IOException {
        Path p = dir.resolve("a.labels");
        writeThree(p);
        assertEquals(LabelsIO.HEADER_BYTES + 3 * 12, Files.size(p));
        try (LabelsIO.Reader r = new LabelsIO.Reader(p)) {
            assertEquals(3, r.frames());
            assertArrayEquals(frame(2).id(), r.read(2).id());
            assertArrayEquals(frame(0).id(), r.read(0).id());
            assertEquals(200, r.read(1).get(3, 2));
        }
    }

    @Test
    void sameContentSameHashDifferentContentDifferentHash() throws IOException {
        String a = writeThree(dir.resolve("a.labels"));
        String b = writeThree(dir.resolve("b.labels"));
        assertEquals(a, b);
        assertEquals(64, a.length());
        String c;
        try (LabelsIO.Writer w = new LabelsIO.Writer(dir.resolve("c.labels"), 4, 3)) {
            w.write(frame(5));
            c = w.hash();
        }
        assertNotEquals(a, c);
    }

    @Test
    void badFilesGiveClearErrors() throws IOException {
        Path p = dir.resolve("a.labels");
        writeThree(p);
        Path bad = dir.resolve("bad.labels");
        Files.copy(p, bad);
        try (RandomAccessFile f = new RandomAccessFile(bad.toFile(), "rw")) {
            f.writeInt(0x12345678);
        }
        IOException magic = assertThrows(IOException.class, () -> new LabelsIO.Reader(bad));
        assertTrue(magic.getMessage().contains("bad magic"));
        Path cut = dir.resolve("cut.labels");
        Files.write(cut, java.util.Arrays.copyOf(Files.readAllBytes(p), LabelsIO.HEADER_BYTES + 20));
        assertTrue(assertThrows(IOException.class, () -> new LabelsIO.Reader(cut)).getMessage().contains("truncated"));
        Path unfinished = dir.resolve("u.labels");
        Files.copy(p, unfinished);
        try (RandomAccessFile f = new RandomAccessFile(unfinished.toFile(), "rw")) {
            f.seek(LabelsIO.FRAME_COUNT_POS);
            f.writeInt(0);
        }
        assertTrue(assertThrows(IOException.class, () -> new LabelsIO.Reader(unfinished)).getMessage().contains("unfinished"));
    }

    @Test
    void wrongGridIsRejected() throws IOException {
        try (LabelsIO.Writer w = new LabelsIO.Writer(dir.resolve("w.labels"), 4, 3)) {
            assertThrows(IllegalArgumentException.class, () -> w.write(new Labels(3, 4, new byte[12])));
            w.write(frame(0));
        }
    }
}
