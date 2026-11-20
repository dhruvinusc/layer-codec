package codec.common;

import codec.common.CmpFormat.CmpException;
import codec.common.CmpFormat.Header;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Encoder side of the brief's part 2: a frame and its macroblock labels go in, raw baseline
 * payload bytes come out, ready for {@link CmpFormat.Writer#writePayload}. EncoderMain calls it
 * for real clips; CodecHarness calls it with made-up labels for testing.
 *
 * <p>Steps: pad to the macroblock grid, give each 8x8 block the label of its 16x16 macroblock,
 * run the 8x8 DCT on R, G and B, quantize with n1 or n2 depending on that label, and write the
 * blocks in scan line order. No segmentation happens here: the labels arrive finished.
 *
 * <p>Thread safety: no shared mutable state; {@link #encodeParallel} splits block rows across a
 * pool, each task writing its own rows, so the bytes are identical to {@link #encode} (tested).
 */
public final class FrameEncoder {

    private FrameEncoder() {
    }

    /** Serial encode. {@code frame} is upright, either true size or already padded. */
    public static byte[] encode(Header h, Types.Frame frame, Types.Labels labels) throws CmpException {
        Types.Frame p = padded(h, frame);
        byte[] types = types(h, labels);
        short[] coef = new short[h.blockCount() * CmpFormat.COEF_PER_BLOCK];
        transformRows(h, p, types, coef, 0, h.blocksY());
        return CmpFormat.encodeBaseline(h, types, coef);
    }

    /** Same bytes as {@link #encode}, block rows split into {@code tasks} jobs on {@code pool}. */
    public static byte[] encodeParallel(Header h, Types.Frame frame, Types.Labels labels,
                                        ExecutorService pool, int tasks)
            throws CmpException, InterruptedException {
        Types.Frame p = padded(h, frame);
        byte[] types = types(h, labels);
        short[] coef = new short[h.blockCount() * CmpFormat.COEF_PER_BLOCK];
        int rows = h.blocksY();
        List<Future<?>> jobs = new ArrayList<>();
        for (int t = 0; t < tasks; t++) {
            int from = rows * t / tasks;
            int to = rows * (t + 1) / tasks;
            jobs.add(pool.submit(() -> transformRows(h, p, types, coef, from, to)));
        }
        for (Future<?> f : jobs) {
            try {
                f.get();
            } catch (ExecutionException e) {
                throw new IllegalStateException("encode worker failed", e.getCause());
            }
        }
        return CmpFormat.encodeBaseline(h, types, coef);
    }

    private static Types.Frame padded(Header h, Types.Frame f) throws CmpException {
        if (f.width() == h.paddedW() && f.height() == h.paddedH()) {
            return f;
        }
        if (f.width() == h.width() && f.height() == h.height()) {
            return Pad.pad(f, h.paddedW(), h.paddedH());
        }
        throw new CmpException("frame is " + f.width() + "x" + f.height() + ", header expects "
                + h.width() + "x" + h.height() + " (padded " + h.paddedW() + "x" + h.paddedH() + ")");
    }

    private static byte[] types(Header h, Types.Labels labels) throws CmpException {
        int cols = h.paddedW() / Types.MB;
        int rows = h.paddedH() / Types.MB;
        if (labels.cols() != cols || labels.rows() != rows) {
            throw new CmpException("labels are " + labels.cols() + "x" + labels.rows()
                    + " macroblocks, frame needs " + cols + "x" + rows);
        }
        return CmpFormat.blockTypes(labels, h.blocksX(), h.blocksY());
    }

    private static void transformRows(Header h, Types.Frame p, byte[] types, short[] coef, int from, int to) {
        double[] c = new double[64];
        double[] tmp = new double[64];
        int bx = h.blocksX();
        int stride = h.paddedW();
        for (int by = from; by < to; by++) {
            for (int b = by * bx; b < (by + 1) * bx; b++) {
                int n = types[b] == 1 ? h.n1() : h.n2();
                int x0 = (b % bx) * Types.BLK;
                int y0 = by * Types.BLK;
                int base = b * CmpFormat.COEF_PER_BLOCK;
                Dct.forward(p.r(), stride, x0, y0, c, tmp);
                Quant.quantizeBlock(c, n, coef, base);
                Dct.forward(p.g(), stride, x0, y0, c, tmp);
                Quant.quantizeBlock(c, n, coef, base + 64);
                Dct.forward(p.b(), stride, x0, y0, c, tmp);
                Quant.quantizeBlock(c, n, coef, base + 128);
            }
        }
    }
}
