package codec.common;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The .cmp file, version 1, FROZEN Nov 11 (03 D7). Big-endian (ByteBuffer default).
 *
 * <pre>
 * Header, 48 bytes
 *   0  4  magic "LYRC"            20 4  padded height        36 1  n1 (0..10)
 *   4  2  version = 1             24 4  fps numerator        37 1  n2 (0..10)
 *   6  2  flags                   28 4  fps denominator      38 2  reserved, written 0
 *   8  4  true width              32 4  frame count *        40 8  footer offset *
 *  12  4  true height                                        (* patched at close)
 *  16  4  padded width
 * flags: bit 0 = zlib payloads, bits 1..3 = mode (0 = baseline), all other bits 0.
 * Per frame: int compressed length, int raw length, payload (zlib'd if bit 0).
 * Baseline payload: every 8x8 block of the padded frame in scan line order, 385 bytes each:
 *   1 byte block_type (0 background, 1 foreground), 64 R, 64 G, 64 B shorts, row-major.
 * Footer: one long offset per frame (points at the frame's compressed-length field).
 * </pre>
 *
 * <p>In memory a baseline frame is {@code byte[] types} (one per 8x8 block, scan order) and
 * {@code short[] coef} (192 per block: R then G then B, block after block).
 */
public final class CmpFormat {

    public static final int MAGIC = 0x4C595243; // "LYRC"
    public static final int VERSION = 1;
    public static final int HEADER_BYTES = 48;
    public static final int FOOTER_OFFSET_POS = 40;
    public static final int FRAME_COUNT_POS = 32;
    public static final int FLAG_ZLIB = 1;
    public static final int MODE_SHIFT = 1;
    public static final int MODE_MASK = 0x7 << MODE_SHIFT;
    public static final int MODE_BASELINE = 0;
    /** P4 option B (PM approved Nov 14): baseline blocks, coefficients in zigzag + run-length. */
    public static final int MODE_RLE = 1;
    /** Coefficients per block: 64 for each of R, G, B. */
    public static final int COEF_PER_BLOCK = 3 * 64;
    /** Bytes per baseline block: 1 type byte + 192 shorts. */
    public static final int BYTES_PER_BLOCK = 1 + COEF_PER_BLOCK * 2;
    private static final int FRAME_PREFIX = 8;
    private static final int KNOWN_FLAG_BITS = FLAG_ZLIB | MODE_MASK;

    private CmpFormat() {
    }

    /** Any problem with a .cmp file: always a clear message, never a silent wrong picture. */
    public static final class CmpException extends IOException {
        private static final long serialVersionUID = 1L;

        public CmpException(String msg) {
            super(msg);
        }
    }

    /** Header fields. frameCount and footerOffset are 0 until the writer closes the file. */
    public record Header(int flags, int width, int height, int paddedW, int paddedH,
                         int fpsNum, int fpsDen, int frameCount, int n1, int n2, long footerOffset) {

        public static Header baseline(Types.VideoInfo info, int n1, int n2, boolean zlib) {
            return new Header(zlib ? FLAG_ZLIB : 0, info.width(), info.height(), info.paddedW(),
                    info.paddedH(), info.fpsNum(), info.fpsDen(), 0, n1, n2, 0);
        }

        public boolean zlib() {
            return (flags & FLAG_ZLIB) != 0;
        }

        public int mode() {
            return (flags & MODE_MASK) >> MODE_SHIFT;
        }

        public int blocksX() {
            return paddedW / Types.BLK;
        }

        public int blocksY() {
            return paddedH / Types.BLK;
        }

        public int blockCount() {
            return blocksX() * blocksY();
        }

        /** Uncompressed payload size of one baseline frame. */
        public int baselineRawBytes() {
            return blockCount() * BYTES_PER_BLOCK;
        }

        /** Checks everything that does not depend on the rest of the file. */
        void validate() throws CmpException {
            if ((flags & ~KNOWN_FLAG_BITS) != 0) {
                throw new CmpException(String.format("unknown flag bits 0x%04x", flags & ~KNOWN_FLAG_BITS));
            }
            if (mode() != MODE_BASELINE && mode() != MODE_RLE) {
                throw new CmpException("unknown mode " + mode()
                        + " (this build knows 0 = baseline, 1 = zigzag + run-length)");
            }
            checkN("n1", n1);
            checkN("n2", n2);
            if (width <= 0 || height <= 0 || paddedW < width || paddedH < height
                    || paddedW % Types.MB != 0 || paddedH % Types.MB != 0) {
                throw new CmpException("bad frame size " + width + "x" + height + " padded " + paddedW + "x" + paddedH);
            }
            if (fpsNum <= 0 || fpsDen <= 0) {
                throw new CmpException("bad frame rate " + fpsNum + "/" + fpsDen);
            }
        }

        private static void checkN(String name, int n) throws CmpException {
            if (n < Quant.MIN_N || n > Quant.MAX_N) {
                throw new CmpException(name + " = " + n + " outside " + Quant.MIN_N + ".." + Quant.MAX_N);
            }
        }
    }

    /**
     * C1: block_type of each 8x8 block in scan line order = 1 if its 16x16 macroblock has any
     * foreground label (1..255), else 0. An 8x8 block at (bx, by) lies in macroblock (bx/2, by/2).
     */
    public static byte[] blockTypes(Types.Labels labels, int blocksX, int blocksY) {
        byte[] t = new byte[blocksX * blocksY];
        int per = Types.MB / Types.BLK;
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                t[by * blocksX + bx] = (byte) (labels.get(bx / per, by / per) != 0 ? 1 : 0);
            }
        }
        return t;
    }

    /** Serializes a baseline frame into its raw (uncompressed) payload. */
    public static byte[] encodeBaseline(Header h, byte[] types, short[] coef) throws CmpException {
        int blocks = h.blockCount();
        if (types.length != blocks || coef.length != blocks * COEF_PER_BLOCK) {
            throw new CmpException("frame has " + types.length + " types / " + coef.length
                    + " coefficients, expected " + blocks + " / " + blocks * COEF_PER_BLOCK);
        }
        ByteBuffer buf = ByteBuffer.allocate(h.baselineRawBytes());
        for (int b = 0; b < blocks; b++) {
            int type = types[b] & 0xFF;
            if (type > 1) {
                throw new CmpException("block " + b + " has block_type " + type + ", must be 0 or 1");
            }
            buf.put((byte) type);
            for (int i = 0; i < COEF_PER_BLOCK; i++) {
                buf.putShort(coef[b * COEF_PER_BLOCK + i]);
            }
        }
        return buf.array();
    }

    /** Parses a raw baseline payload into {@code types} and {@code coef}; validates block_type. */
    public static void decodeBaseline(Header h, byte[] raw, byte[] types, short[] coef) throws CmpException {
        int blocks = h.blockCount();
        if (raw.length != h.baselineRawBytes()) {
            throw new CmpException("payload is " + raw.length + " bytes, expected " + h.baselineRawBytes());
        }
        ByteBuffer buf = ByteBuffer.wrap(raw);
        for (int b = 0; b < blocks; b++) {
            int type = buf.get() & 0xFF;
            if (type > 1) {
                throw new CmpException("block " + b + " has block_type " + type + ", must be 0 or 1");
            }
            types[b] = (byte) type;
            for (int i = 0; i < COEF_PER_BLOCK; i++) {
                coef[b * COEF_PER_BLOCK + i] = buf.getShort();
            }
        }
    }

    static byte[] headerBytes(Header h, int frameCount, long footerOffset) {
        ByteBuffer b = ByteBuffer.allocate(HEADER_BYTES);
        b.putInt(MAGIC).putShort((short) VERSION).putShort((short) h.flags())
                .putInt(h.width()).putInt(h.height()).putInt(h.paddedW()).putInt(h.paddedH())
                .putInt(h.fpsNum()).putInt(h.fpsDen()).putInt(frameCount)
                .put((byte) h.n1()).put((byte) h.n2()).putShort((short) 0).putLong(footerOffset);
        return b.array();
    }

    static Header parseHeader(byte[] bytes) throws CmpException {
        ByteBuffer b = ByteBuffer.wrap(bytes);
        int magic = b.getInt();
        if (magic != MAGIC) {
            throw new CmpException(String.format("not a .cmp file (magic 0x%08x, expected LYRC)", magic));
        }
        int version = b.getShort() & 0xFFFF;
        if (version != VERSION) {
            throw new CmpException("unsupported .cmp version " + version + " (this build reads " + VERSION + ")");
        }
        int flags = b.getShort() & 0xFFFF;
        int w = b.getInt();
        int hh = b.getInt();
        int pw = b.getInt();
        int ph = b.getInt();
        int fn = b.getInt();
        int fd = b.getInt();
        int count = b.getInt();
        int n1 = b.get() & 0xFF;
        int n2 = b.get() & 0xFF;
        b.getShort(); // reserved
        long footer = b.getLong();
        Header h = new Header(flags, w, hh, pw, ph, fn, fd, count, n1, n2, footer);
        h.validate();
        return h;
    }

    // ------------------------------------------------------------------------------------------

    /**
     * Streaming writer. Writes the header with frame count 0 and footer offset 0, so a file left
     * behind by a crashed encoder is recognized as unfinished. close() writes the footer and
     * patches both fields.
     */
    public static final class Writer implements AutoCloseable {
        private final Path path;
        private final Header header;
        private final OutputStream out;
        private long pos;
        private long[] offsets = new long[64];
        private int count;
        private boolean closed;

        public Writer(Path path, Header header) throws IOException {
            header.validate();
            this.path = path;
            this.header = header;
            this.out = new BufferedOutputStream(Files.newOutputStream(path), 1 << 20);
            out.write(headerBytes(header, 0, 0));
            pos = HEADER_BYTES;
        }

        public Header header() {
            return header;
        }

        /** Writes one baseline frame. */
        public void writeFrame(byte[] types, short[] coef) throws IOException {
            writePayload(encodeBaseline(header, types, coef));
        }

        /** Writes an already serialized raw payload (zlib applied here if the flag is set). */
        public void writePayload(byte[] raw) throws IOException {
            byte[] stored = header.zlib() ? Zio.deflate(raw, 0, raw.length) : raw;
            if (count == offsets.length) {
                offsets = java.util.Arrays.copyOf(offsets, count * 2);
            }
            offsets[count++] = pos;
            out.write(ByteBuffer.allocate(FRAME_PREFIX).putInt(stored.length).putInt(raw.length).array());
            out.write(stored);
            pos += FRAME_PREFIX + stored.length;
        }

        public int frameCount() {
            return count;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            long footer = pos;
            ByteBuffer f = ByteBuffer.allocate(count * 8);
            for (int i = 0; i < count; i++) {
                f.putLong(offsets[i]);
            }
            out.write(f.array());
            out.close();
            try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
                raf.seek(FRAME_COUNT_POS);
                raf.writeInt(count); // RandomAccessFile writes big-endian
                raf.seek(FOOTER_OFFSET_POS);
                raf.writeLong(footer);
            }
        }
    }

    // ------------------------------------------------------------------------------------------

    /**
     * Reader with random access by frame index. Uses positional reads only, so {@link #payload}
     * may be called from several threads at once (P3 decode-ahead).
     */
    public static final class Reader implements AutoCloseable {
        private final FileChannel ch;
        private final Header header;
        private final long[] offsets;

        public Reader(Path path) throws IOException {
            ch = FileChannel.open(path, StandardOpenOption.READ);
            try {
                long size = ch.size();
                if (size < HEADER_BYTES) {
                    throw new CmpException("file is " + size + " bytes, shorter than the 48-byte header");
                }
                header = parseHeader(readAt(0, HEADER_BYTES));
                offsets = readFooter(size);
            } catch (IOException | RuntimeException e) {
                ch.close();
                throw e;
            }
        }

        private long[] readFooter(long size) throws IOException {
            long footer = header.footerOffset();
            if (footer == 0) {
                throw new CmpException("unfinished .cmp file (footer offset 0): the encoder did not close it");
            }
            int n = header.frameCount();
            if (n < 0 || footer < HEADER_BYTES || footer + 8L * n != size) {
                throw new CmpException("footer does not match file size (frames " + n + ", footer at "
                        + footer + ", size " + size + "): truncated or corrupt");
            }
            ByteBuffer b = ByteBuffer.wrap(readAt(footer, 8 * n));
            long[] off = new long[n];
            long prev = HEADER_BYTES - 1;
            for (int i = 0; i < n; i++) {
                off[i] = b.getLong();
                if (off[i] <= prev || off[i] + FRAME_PREFIX > footer) {
                    throw new CmpException("frame " + i + " offset " + off[i] + " is out of order or out of range");
                }
                prev = off[i];
            }
            return off;
        }

        public Header header() {
            return header;
        }

        public int frameCount() {
            return offsets.length;
        }

        /** Byte position of frame k's compressed-length field (from the footer). */
        public long offset(int k) {
            return offsets[k];
        }

        /** {stored length, raw length} of frame k, as written in its 8-byte prefix. */
        public int[] lengths(int k) throws IOException {
            ByteBuffer pre = ByteBuffer.wrap(readAt(offsets[k], FRAME_PREFIX));
            return new int[] {pre.getInt(), pre.getInt()};
        }

        /** Raw (inflated) payload of frame k, length-checked. */
        public byte[] payload(int k) throws IOException {
            if (k < 0 || k >= offsets.length) {
                throw new CmpException("frame " + k + " out of range 0.." + (offsets.length - 1));
            }
            ByteBuffer pre = ByteBuffer.wrap(readAt(offsets[k], FRAME_PREFIX));
            int stored = pre.getInt();
            int raw = pre.getInt();
            long end = k + 1 < offsets.length ? offsets[k + 1] : header.footerOffset();
            if (stored < 0 || raw < 0 || offsets[k] + FRAME_PREFIX + stored != end) {
                throw new CmpException("frame " + k + " length " + stored + " does not fit its slot");
            }
            if (!header.zlib() && stored != raw) {
                throw new CmpException("frame " + k + ": zlib is off but compressed length " + stored
                        + " != raw length " + raw);
            }
            if (header.mode() == MODE_BASELINE && raw != header.baselineRawBytes()) {
                throw new CmpException("frame " + k + " raw length " + raw + ", baseline needs "
                        + header.baselineRawBytes());
            }
            byte[] data = readAt(offsets[k] + FRAME_PREFIX, stored);
            if (!header.zlib()) {
                return data;
            }
            byte[] out = new byte[raw];
            try {
                Zio.inflate(data, 0, stored, out, raw);
            } catch (IOException e) {
                throw new CmpException("frame " + k + ": " + e.getMessage());
            }
            return out;
        }

        /** Reads baseline frame k into {@code types} and {@code coef}. */
        public void readFrame(int k, byte[] types, short[] coef) throws IOException {
            decodeBaseline(header, payload(k), types, coef);
        }

        private byte[] readAt(long position, int len) throws IOException {
            ByteBuffer b = ByteBuffer.allocate(len);
            while (b.hasRemaining()) {
                int r = ch.read(b, position + b.position());
                if (r < 0) {
                    throw new CmpException("unexpected end of file at byte " + (position + b.position()));
                }
            }
            return b.array();
        }

        @Override
        public void close() throws IOException {
            ch.close();
        }
    }
}
