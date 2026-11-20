package codec.encoder;

import codec.common.Types.Labels;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * P1.10: per-frame macroblock labels on disk ({@code out/<clip>.labels}), so segmentation can be
 * inspected, compared run to run, and replayed without re-running motion search.
 *
 * <p>Layout, big-endian: magic {@code LYLB}, version (int), cols (int), rows (int), frame count
 * (int, patched at close; 0 means unfinished), then one byte per macroblock per frame, row-major,
 * 0 = background, 1..255 = foreground region id. The SHA-256 of the label bytes in frame order is
 * the determinism check: two runs with the same input and settings must print the same hash.
 */
public final class LabelsIO {

    public static final int MAGIC = 0x4C594C42; // "LYLB"
    public static final int VERSION = 1;
    public static final int HEADER_BYTES = 20;
    static final int FRAME_COUNT_POS = 16;

    private LabelsIO() {
    }

    /** Streams labels frame by frame; frame count is written at close. */
    public static final class Writer implements AutoCloseable {
        private final RandomAccessFile file;
        private final int cols;
        private final int rows;
        private final MessageDigest sha;
        private int frames;

        public Writer(Path path, int cols, int rows) throws IOException {
            this.cols = cols;
            this.rows = rows;
            this.sha = sha256();
            this.file = new RandomAccessFile(path.toFile(), "rw");
            file.setLength(0);
            ByteBuffer b = ByteBuffer.allocate(HEADER_BYTES);
            b.putInt(MAGIC).putInt(VERSION).putInt(cols).putInt(rows).putInt(0);
            file.write(b.array());
        }

        public void write(Labels l) throws IOException {
            if (l.cols() != cols || l.rows() != rows) {
                throw new IllegalArgumentException("labels are " + l.cols() + "x" + l.rows() + ", file is " + cols + "x" + rows);
            }
            file.write(l.id());
            sha.update(l.id());
            frames++;
        }

        public int frames() {
            return frames;
        }

        /** Hex SHA-256 of all label bytes written so far. */
        public String hash() throws IOException {
            try {
                return hex(((MessageDigest) sha.clone()).digest());
            } catch (CloneNotSupportedException e) {
                throw new IOException("SHA-256 digest cannot be cloned", e);
            }
        }

        @Override
        public void close() throws IOException {
            file.seek(FRAME_COUNT_POS);
            file.writeInt(frames);
            file.close();
        }
    }

    /** Random-access reader. */
    public static final class Reader implements AutoCloseable {
        private final RandomAccessFile file;
        private final int cols;
        private final int rows;
        private final int frames;

        public Reader(Path path) throws IOException {
            file = new RandomAccessFile(path.toFile(), "r");
            if (file.length() < HEADER_BYTES) {
                file.close();
                throw new IOException(path + ": too short for a labels header");
            }
            int magic = file.readInt();
            int version = file.readInt();
            cols = file.readInt();
            rows = file.readInt();
            frames = file.readInt();
            String problem = magic != MAGIC ? "not a labels file (bad magic)"
                    : version != VERSION ? "unsupported version " + version
                    : cols <= 0 || rows <= 0 ? "bad grid " + cols + "x" + rows
                    : frames == 0 ? "unfinished file (frame count 0)"
                    : file.length() != HEADER_BYTES + (long) frames * cols * rows ? "truncated or oversized file"
                    : null;
            if (problem != null) {
                file.close();
                throw new IOException(path + ": " + problem);
            }
        }

        public int cols() {
            return cols;
        }

        public int rows() {
            return rows;
        }

        public int frames() {
            return frames;
        }

        public Labels read(int k) throws IOException {
            if (k < 0 || k >= frames) {
                throw new IndexOutOfBoundsException("frame " + k + " of " + frames);
            }
            byte[] id = new byte[cols * rows];
            file.seek(HEADER_BYTES + (long) k * id.length);
            file.readFully(id);
            return new Labels(cols, rows, id);
        }

        @Override
        public void close() throws IOException {
            file.close();
        }
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }

    static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
