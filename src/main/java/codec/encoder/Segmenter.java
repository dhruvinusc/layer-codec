package codec.encoder;

import codec.common.Types;

/**
 * Foreground/background labelling from motion only (03 D3). Built up step by step in P1:
 * reliability (P1.4) first; camera motion, residuals, grouping, cleaning and temporal voting follow.
 *
 * <p>All thresholds live in {@link SegConfig} (one record, tuned on all 4 clips together in P1.9).
 * The frozen {@code codec.common.Config} is not touched (P1-D5).
 */
public final class Segmenter {

    private Segmenter() {
    }

    /**
     * Segmentation thresholds.
     *
     * @param minTextureVar a block needs at least this Y variance (std dev 10 at 100) to be matched
     *                      reliably; flatter blocks (sky, road, padding) match many positions
     * @param maxMatchMad   a block whose best match still differs by more than this many levels per
     *                      pixel did not really find itself in the previous frame (occlusion, blur)
     */
    public record SegConfig(int minTextureVar, int maxMatchMad) {
        public static final SegConfig DEFAULT = new SegConfig(100, 12);
    }

    /**
     * P1.4: true where a block's vector can be trusted. Unreliable blocks are not evidence for or
     * against motion; later steps give them their neighbours' label.
     */
    public static boolean[] reliable(int[] y, int w, int h, MotionEstimator.Result me, SegConfig c) {
        int cols = w / Types.MB;
        int rows = h / Types.MB;
        boolean[] ok = new boolean[cols * rows];
        long maxSad = (long) c.maxMatchMad() * Types.MB * Types.MB;
        for (int i = 0; i < ok.length; i++) {
            int x0 = (i % cols) * Types.MB;
            int y0 = (i / cols) * Types.MB;
            ok[i] = blockVariance(y, w, x0, y0) >= c.minTextureVar() && me.sadBest()[i] <= maxSad;
        }
        return ok;
    }

    /** Integer variance of the 16x16 Y block at (x0, y0). */
    public static int blockVariance(int[] y, int w, int x0, int y0) {
        long sum = 0;
        long sq = 0;
        for (int r = 0; r < Types.MB; r++) {
            int row = (y0 + r) * w + x0;
            for (int k = 0; k < Types.MB; k++) {
                int v = y[row + k];
                sum += v;
                sq += (long) v * v;
            }
        }
        int n = Types.MB * Types.MB;
        return (int) ((sq - sum * sum / n) / n);
    }
}
