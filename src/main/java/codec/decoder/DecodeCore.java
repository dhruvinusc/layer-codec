package codec.decoder;

import codec.common.CmpFormat;
import codec.common.CmpFormat.CmpException;
import codec.common.CmpFormat.Header;
import codec.common.Dct;
import codec.common.Quant;
import codec.common.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Turns one baseline frame payload into a picture (brief part 3, checklist D1 to D3).
 *
 * <p>Per 8x8 block: read block_type, pick n1 (foreground) or n2 (background), multiply every
 * coefficient by 2^n, inverse DCT, round and clamp to 0..255. Pixels in the padding (beyond the
 * true width/height) are never written, so the output is already cropped (03 D5).
 *
 * <p>Thread safety: no static mutable state. Each thread brings its own {@link Scratch}, so P3
 * can decode several frames at once, and {@link #decodeParallel} can split one frame by block
 * rows. Threads write disjoint rows, so output is identical to the serial version (tested).
 */
public final class DecodeCore {

    private DecodeCore() {
    }

    /** Decoded frame plus the 8x8 block_type map (padded grid, scan order) for label overlays. */
    public record Decoded(Types.Frame frame, byte[] types) {
    }

    /** Per-thread working memory, sized for one header. Never share between threads. */
    public static final class Scratch {
        final byte[] types;
        final short[] coef;
        final double[] c = new double[64];
        final double[] px = new double[64];
        final double[] tmp = new double[64];

        public Scratch(Header h) {
            types = new byte[h.blockCount()];
            coef = new short[h.blockCount() * CmpFormat.COEF_PER_BLOCK];
        }
    }

    /** Serial decode with fresh scratch. */
    public static Decoded decode(Header h, byte[] raw) throws CmpException {
        return decode(h, raw, new Scratch(h));
    }

    /** Serial decode reusing the caller's scratch (one per thread). */
    public static Decoded decode(Header h, byte[] raw, Scratch s) throws CmpException {
        CmpFormat.decodeBaseline(h, raw, s.types, s.coef);
        Types.Frame out = Types.Frame.allocate(h.width(), h.height());
        reconstructRows(h, s.types, s.coef, out, 0, h.blocksY(), s.c, s.px, s.tmp);
        return new Decoded(out, s.types.clone());
    }

    /**
     * Decodes one frame with its block rows split across {@code pool}. Used to measure single
     * frame speed with all cores (P2.9); P3 parallelizes across frames instead.
     */
    public static Decoded decodeParallel(Header h, byte[] raw, ExecutorService pool, int tasks)
            throws CmpException, InterruptedException {
        byte[] types = new byte[h.blockCount()];
        short[] coef = new short[h.blockCount() * CmpFormat.COEF_PER_BLOCK];
        CmpFormat.decodeBaseline(h, raw, types, coef);
        Types.Frame out = Types.Frame.allocate(h.width(), h.height());
        int rows = h.blocksY();
        List<Future<?>> jobs = new ArrayList<>();
        for (int t = 0; t < tasks; t++) {
            int from = rows * t / tasks;
            int to = rows * (t + 1) / tasks;
            jobs.add(pool.submit(() -> reconstructRows(h, types, coef, out, from, to,
                    new double[64], new double[64], new double[64])));
        }
        for (Future<?> f : jobs) {
            try {
                f.get();
            } catch (ExecutionException e) {
                throw new IllegalStateException("decode worker failed", e.getCause());
            }
        }
        return new Decoded(out, types);
    }

    /** Reconstructs 8x8 block rows [rowFrom, rowTo) into {@code out} (true size, cropped). */
    static void reconstructRows(Header h, byte[] types, short[] coef, Types.Frame out,
                                int rowFrom, int rowTo, double[] c, double[] px, double[] tmp) {
        int bx = h.blocksX();
        for (int by = rowFrom; by < rowTo; by++) {
            for (int b = by * bx; b < (by + 1) * bx; b++) {
                // D2: the block's own type decides its quantizer step.
                int n = (types[b] & 0xFF) == 1 ? h.n1() : h.n2();
                int base = b * CmpFormat.COEF_PER_BLOCK;
                int x0 = (b % bx) * Types.BLK;
                int y0 = by * Types.BLK;
                reconstructChannel(coef, base, n, out.r(), out, x0, y0, c, px, tmp);
                reconstructChannel(coef, base + 64, n, out.g(), out, x0, y0, c, px, tmp);
                reconstructChannel(coef, base + 128, n, out.b(), out, x0, y0, c, px, tmp);
            }
        }
    }

    private static void reconstructChannel(short[] coef, int off, int n, int[] plane, Types.Frame out,
                                           int x0, int y0, double[] c, double[] px, double[] tmp) {
        Quant.dequantizeBlock(coef, off, n, c);
        Dct.inverseZeroSkip(c, px, tmp);
        int w = out.width();
        int yEnd = Math.min(Types.BLK, out.height() - y0);
        int xEnd = Math.min(Types.BLK, w - x0);
        for (int y = 0; y < yEnd; y++) {
            for (int x = 0; x < xEnd; x++) {
                plane[(y0 + y) * w + x0 + x] = Quant.toPixel(px[y * Types.BLK + x]);
            }
        }
    }
}
