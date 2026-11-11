package codec.common;

import java.io.IOException;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * zlib wrapper for .cmp frame payloads (03 D7). Lossless: it only repacks the bytes, so the
 * coefficients the decoder sees are exactly the ones the encoder wrote. The only place in the
 * codec that touches java.util.zip (03 D1 isolation).
 *
 * <p>Deflater and Inflater hold native memory that the garbage collector does not see, so every
 * instance is created per call and released with {@code end()} in a finally block. Both loops
 * run until the stream says it is finished, because one call may not produce all the output.
 * No shared state, so frames can be inflated on several threads at once (P3).
 */
public final class Zio {

    /** Level 1 (fastest). P0 speed gate: 10.3x on a SAL frame; higher levels cost encode time. */
    public static final int LEVEL = 1;

    private Zio() {
    }

    /** Compresses {@code raw[off .. off+len)} and returns exactly the compressed bytes. */
    public static byte[] deflate(byte[] raw, int off, int len) {
        Deflater d = new Deflater(LEVEL);
        try {
            d.setInput(raw, off, len);
            d.finish();
            // zlib worst case is a little larger than the input; start there and grow if needed.
            byte[] out = new byte[len + len / 1000 + 64];
            int n = 0;
            while (!d.finished()) {
                if (n == out.length) {
                    out = Arrays.copyOf(out, out.length * 2);
                }
                n += d.deflate(out, n, out.length - n);
            }
            return Arrays.copyOf(out, n);
        } finally {
            d.end();
        }
    }

    /**
     * Inflates {@code comp[off .. off+len)} into {@code out[0 .. expected)}. The output length is
     * known from the frame header, so anything else is a corrupt file.
     */
    public static void inflate(byte[] comp, int off, int len, byte[] out, int expected) throws IOException {
        Inflater inf = new Inflater();
        try {
            inf.setInput(comp, off, len);
            int n = 0;
            while (!inf.finished()) {
                int got = inf.inflate(out, n, expected - n);
                n += got;
                if (got == 0 && !inf.finished()) {
                    if (n == expected) {
                        throw new IOException("zlib stream longer than the expected " + expected + " bytes");
                    }
                    throw new IOException("zlib stream truncated after " + n + " of " + expected + " bytes");
                }
            }
            if (n != expected) {
                throw new IOException("zlib stream gave " + n + " bytes, expected " + expected);
            }
        } catch (DataFormatException e) {
            throw new IOException("corrupt zlib stream: " + e.getMessage(), e);
        } finally {
            inf.end();
        }
    }
}
