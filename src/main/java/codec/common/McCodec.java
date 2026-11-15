package codec.common;

import codec.common.CmpFormat.CmpException;
import codec.common.CmpFormat.Header;
import java.io.ByteArrayOutputStream;

/**
 * P4 option A (.cmp mode 2): motion-compensated residual coding, shared by encoder and decoder.
 *
 * <p>Idea: most of a frame is the previous frame moved a little by the camera. For every 16x16
 * macroblock we keep its motion vector (from Window A's motion search), predict each 8x8 block
 * from where it was in the previous RECONSTRUCTED frame, and code only the difference (residual)
 * with the same 8x8 DCT and 2^n quantization by layer as the baseline, then zigzag + run-length.
 *
 * <p>Why the reconstructed frame: the decoder only has its own decoded frames. If the encoder
 * predicted from the original frames, each frame's small coding error would be built on by the
 * next and grow (drift). Encoder and decoder therefore call the SAME {@link #reconstruct}
 * routine on the same numbers, so their reference frames are bit-identical (tested).
 *
 * <p>Groups: frame k is an I frame (coded from scratch) when k is a multiple of {@link #GOP},
 * otherwise a P frame (predicted from frame k-1). A group (I frame plus its P frames) can be
 * decoded on its own, which is what lets the player decode several groups at once (GopPipeline) and limits a step back to at
 * most GOP - 1 extra decodes.
 *
 * <p>Payload of one frame: 1 byte frame type (0 = I, 1 = P); P frames then 2 signed bytes (dx, dy)
 * per macroblock in scan line order (vector convention 03 D12: block at (x, y) in frame t is
 * predicted from (x + dx, y + dy) in frame t-1); then the blocks exactly as in mode 1 (block_type +
 * zigzag run-length coefficients, here of the residual). An I frame uses the constant prediction 128,
 * which makes it the same transform as the baseline.
 */
public final class McCodec {

    /** I frame every 2 frames (P4-L3, PM Nov 15: GOP sweep, baseline quality at 35 to 52% smaller). */
    public static final int GOP = 2;
    static final int FRAME_I = 0;
    static final int FRAME_P = 1;
    private static final int MID = 128;

    private McCodec() {
    }

    /** True if frame k must be an I frame. */
    public static boolean isIntra(int k) {
        return k % GOP == 0;
    }

    /**
     * Reconstructed reference frame (padded size, R G B planes) plus scratch. One per encoder and
     * one per decoding thread; never shared.
     */
    public static final class State {
        final int pw;
        final int ph;
        int[][] ref;
        int[][] cur;
        boolean valid;
        final double[] c = new double[64];
        final double[] px = new double[64];
        final double[] tmp = new double[64];
        final int[] blk = new int[64];
        final int[] pred = new int[64];

        public State(int paddedW, int paddedH) {
            pw = paddedW;
            ph = paddedH;
            ref = new int[3][pw * ph];
            cur = new int[3][pw * ph];
        }

        /** The latest reconstructed frame, cropped to the true size (what the viewer sees). */
        public Types.Frame frame(int w, int h) {
            Types.Frame f = Types.Frame.allocate(w, h);
            int[][] dst = {f.r(), f.g(), f.b()};
            for (int ch = 0; ch < 3; ch++) {
                for (int y = 0; y < h; y++) {
                    System.arraycopy(ref[ch], y * pw, dst[ch], y * w, w);
                }
            }
            return f;
        }

        private void swap() {
            int[][] t = ref;
            ref = cur;
            cur = t;
            valid = true;
        }
    }

    /**
     * Encodes padded frame {@code frame} as frame k and updates {@code st} to its reconstruction.
     * {@code mv} is the macroblock motion field of frame k against frame k-1 (ignored for I frames).
     */
    public static byte[] encode(Header h, State st, Types.Frame frame, byte[] types, Types.MotionField mv, int k)
            throws CmpException {
        boolean intra = isIntra(k);
        checkFrame(h, st, intra, frame.width(), frame.height());
        int mbCols = h.paddedW() / Types.MB;
        int mbRows = h.paddedH() / Types.MB;
        if (!intra && (mv == null || mv.cols() != mbCols || mv.rows() != mbRows)) {
            throw new CmpException("frame " + k + ": P frame needs a " + mbCols + "x" + mbRows + " motion field");
        }
        short[] coef = new short[h.blockCount() * CmpFormat.COEF_PER_BLOCK];
        int[][] in = {frame.r(), frame.g(), frame.b()};
        for (int b = 0; b < h.blockCount(); b++) {
            int x0 = (b % h.blocksX()) * 8;
            int y0 = (b / h.blocksX()) * 8;
            int n = types[b] == 1 ? h.n1() : h.n2();
            int mb = (y0 / Types.MB) * mbCols + x0 / Types.MB;
            int dx = intra ? 0 : mv.dx()[mb];
            int dy = intra ? 0 : mv.dy()[mb];
            for (int ch = 0; ch < 3; ch++) {
                predict(st, intra, ch, x0, y0, dx, dy);
                int[] plane = in[ch];
                for (int i = 0; i < 64; i++) {
                    st.blk[i] = plane[(y0 + (i >> 3)) * st.pw + x0 + (i & 7)] - st.pred[i] + MID;
                }
                Dct.forward(st.blk, 8, 0, 0, st.c, st.tmp); // subtracts 128: the DCT of the residual
                int off = b * CmpFormat.COEF_PER_BLOCK + ch * 64;
                Quant.quantizeBlock(st.c, n, coef, off);
                reconstruct(st, coef, off, n, ch, x0, y0);
            }
        }
        st.swap();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(intra ? FRAME_I : FRAME_P);
        if (!intra) {
            for (int i = 0; i < mbCols * mbRows; i++) {
                out.write(vectorByte(mv.dx()[i], k));
                out.write(vectorByte(mv.dy()[i], k));
            }
        }
        out.writeBytes(RunLength.encode(h, types, coef));
        return out.toByteArray();
    }

    /**
     * Decodes frame k's payload into {@code st} (the reconstruction becomes the new reference);
     * {@code types} receives the block types. {@code coef} is caller scratch.
     */
    public static void decode(Header h, State st, byte[] payload, int k, byte[] types, short[] coef)
            throws CmpException {
        if (payload.length < 1) {
            throw new CmpException("frame " + k + ": empty mode 2 payload");
        }
        int type = payload[0] & 0xFF;
        boolean intra = isIntra(k);
        if (type != (intra ? FRAME_I : FRAME_P)) {
            throw new CmpException("frame " + k + " has frame type " + type + ", expected " + (intra ? "I (0)" : "P (1)"));
        }
        checkFrame(h, st, intra, h.paddedW(), h.paddedH());
        int mbCols = h.paddedW() / Types.MB;
        int mbs = mbCols * (h.paddedH() / Types.MB);
        int start = 1 + (intra ? 0 : 2 * mbs);
        if (payload.length < start) {
            throw new CmpException("frame " + k + ": mode 2 payload too short for its motion vectors");
        }
        RunLength.decode(h, payload, start, types, coef);
        for (int b = 0; b < h.blockCount(); b++) {
            int x0 = (b % h.blocksX()) * 8;
            int y0 = (b / h.blocksX()) * 8;
            int n = types[b] == 1 ? h.n1() : h.n2();
            int mb = (y0 / Types.MB) * mbCols + x0 / Types.MB;
            int dx = intra ? 0 : payload[1 + 2 * mb];
            int dy = intra ? 0 : payload[2 + 2 * mb];
            for (int ch = 0; ch < 3; ch++) {
                predict(st, intra, ch, x0, y0, dx, dy);
                reconstruct(st, coef, b * CmpFormat.COEF_PER_BLOCK + ch * 64, n, ch, x0, y0);
            }
        }
        st.swap();
    }

    private static void checkFrame(Header h, State st, boolean intra, int w, int hh) throws CmpException {
        if (w != h.paddedW() || hh != h.paddedH() || st.pw != h.paddedW() || st.ph != h.paddedH()) {
            throw new CmpException("mode 2 works on padded " + h.paddedW() + "x" + h.paddedH() + " frames");
        }
        if (!intra && !st.valid) {
            throw new CmpException("P frame without a reference: decoding must start at an I frame");
        }
    }

    private static int vectorByte(int v, int k) throws CmpException {
        if (v < Byte.MIN_VALUE || v > Byte.MAX_VALUE) {
            throw new CmpException("frame " + k + ": motion vector component " + v + " outside -128..127");
        }
        return v & 0xFF;
    }

    /** Prediction of one 8x8 block of one channel into st.pred: 128 for I, else the moved reference. */
    private static void predict(State st, boolean intra, int ch, int x0, int y0, int dx, int dy) {
        if (intra) {
            java.util.Arrays.fill(st.pred, MID);
            return;
        }
        int[] ref = st.ref[ch];
        for (int i = 0; i < 64; i++) {
            int x = Math.max(0, Math.min(st.pw - 1, x0 + (i & 7) + dx));
            int y = Math.max(0, Math.min(st.ph - 1, y0 + (i >> 3) + dy));
            st.pred[i] = ref[y * st.pw + x];
        }
    }

    /**
     * THE reconstruction both sides run: dequantize, inverse DCT (which adds 128 back), remove the
     * 128, add the prediction, round and clamp to 0..255. Same function, same inputs: same pixels.
     */
    private static void reconstruct(State st, short[] coef, int off, int n, int ch, int x0, int y0) {
        Quant.dequantizeBlock(coef, off, n, st.c);
        Dct.inverseZeroSkip(st.c, st.px, st.tmp);
        int[] dst = st.cur[ch];
        for (int i = 0; i < 64; i++) {
            dst[(y0 + (i >> 3)) * st.pw + x0 + (i & 7)] = Quant.toPixel(st.px[i] - MID + st.pred[i]);
        }
    }
}
