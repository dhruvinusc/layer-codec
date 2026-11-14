package codec.common;

import codec.common.CmpFormat.CmpException;
import codec.common.CmpFormat.Header;
import java.io.ByteArrayOutputStream;

/**
 * P4 option B (.cmp mode 1): the baseline blocks, with each channel's 64 coefficients written in
 * zigzag order and run-length coded. Lossless: the decoder gets exactly the same quantized
 * coefficients as from the baseline layout, so every decoded pixel is identical.
 *
 * <p>Zigzag visits the 64 coefficients from low to high frequency (the JPEG order). After
 * quantization the high frequencies are mostly zero, so in zigzag order the zeros sit together
 * at the end. Run-length then stores only the nonzero values, each with the number of zeros
 * before it, and an end-of-block marker. An all-zero channel costs 1 byte instead of 128.
 *
 * <p>Layout per 8x8 block, scan line order (as the baseline): 1 byte block_type, then for R, G,
 * B: pairs of (1 byte run of zeros 0..63, 2 bytes signed value, big-endian), ended by
 * {@link #EOB}. zlib still wraps the whole frame payload (03 D7).
 */
public final class RunLength {

    /** End of block. Never a valid run (runs are 0..63). */
    static final int EOB = 0xFF;

    /** ZIGZAG[i] = natural row-major index (v * 8 + u) of the i-th coefficient in zigzag order. */
    static final int[] ZIGZAG = buildZigzag();

    private RunLength() {
    }

    private static int[] buildZigzag() {
        int[] z = new int[64];
        int i = 0;
        for (int s = 0; s <= 14; s++) { // s = u + v, one anti-diagonal at a time
            if (s % 2 == 0) {
                for (int v = Math.min(s, 7); v >= Math.max(0, s - 7); v--) {
                    z[i++] = v * 8 + (s - v);
                }
            } else {
                for (int v = Math.max(0, s - 7); v <= Math.min(s, 7); v++) {
                    z[i++] = v * 8 + (s - v);
                }
            }
        }
        return z;
    }

    /** Serializes one frame (block types + quantized coefficients, baseline layout) as mode 1. */
    public static byte[] encode(Header h, byte[] types, short[] coef) throws CmpException {
        int blocks = h.blockCount();
        if (types.length != blocks || coef.length != blocks * CmpFormat.COEF_PER_BLOCK) {
            throw new CmpException("frame has " + types.length + " types / " + coef.length
                    + " coefficients, expected " + blocks + " / " + blocks * CmpFormat.COEF_PER_BLOCK);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(blocks * 8);
        for (int b = 0; b < blocks; b++) {
            int type = types[b] & 0xFF;
            if (type > 1) {
                throw new CmpException("block " + b + " has block_type " + type + ", must be 0 or 1");
            }
            out.write(type);
            for (int ch = 0; ch < 3; ch++) {
                int base = b * CmpFormat.COEF_PER_BLOCK + ch * 64;
                int run = 0;
                for (int i = 0; i < 64; i++) {
                    short c = coef[base + ZIGZAG[i]];
                    if (c == 0) {
                        run++;
                    } else {
                        out.write(run);
                        out.write((c >> 8) & 0xFF);
                        out.write(c & 0xFF);
                        run = 0;
                    }
                }
                out.write(EOB);
            }
        }
        return out.toByteArray();
    }

    /** Parses a mode-1 payload into {@code types} and {@code coef} (baseline layout); validates it. */
    public static void decode(Header h, byte[] in, byte[] types, short[] coef) throws CmpException {
        int blocks = h.blockCount();
        int p = 0;
        try {
            for (int b = 0; b < blocks; b++) {
                int type = in[p++] & 0xFF;
                if (type > 1) {
                    throw new CmpException("block " + b + " has block_type " + type + ", must be 0 or 1");
                }
                types[b] = (byte) type;
                for (int ch = 0; ch < 3; ch++) {
                    int base = b * CmpFormat.COEF_PER_BLOCK + ch * 64;
                    java.util.Arrays.fill(coef, base, base + 64, (short) 0);
                    int i = 0;
                    while (true) {
                        int run = in[p++] & 0xFF;
                        if (run == EOB) {
                            break;
                        }
                        i += run;
                        if (i >= 64) {
                            throw new CmpException("block " + b + " channel " + ch + ": run passes coefficient 63");
                        }
                        coef[base + ZIGZAG[i]] = (short) (((in[p] & 0xFF) << 8) | (in[p + 1] & 0xFF));
                        p += 2;
                        i++;
                    }
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new CmpException("mode 1 payload ends early at byte " + in.length + " (truncated or corrupt)");
        }
        if (p != in.length) {
            throw new CmpException("mode 1 payload has " + (in.length - p) + " extra bytes after the last block");
        }
    }
}
