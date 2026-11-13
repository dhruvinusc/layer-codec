package codec.encoder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codec.common.Types;
import codec.common.Types.MotionField;
import codec.encoder.GlobalMotion.GmConfig;
import codec.encoder.GlobalMotion.Kind;
import codec.encoder.GlobalMotion.Model;
import java.util.Random;
import org.junit.jupiter.api.Test;

class GlobalMotionTest {

    static final int W = 1920;
    static final int H = 1088;
    /** A mild perspective pan, like Stairs: shift, slight rotation and zoom, small perspective terms. */
    static final double[] TRUE_H = {1.004, 0.006, -7.0, -0.005, 1.003, 2.5, 2.0e-6, -1.5e-6, 1};

    private static double[] apply(double[] h, double x, double y) {
        double w = h[6] * x + h[7] * y + h[8];
        return new double[] {(h[0] * x + h[1] * y + h[2]) / w, (h[3] * x + h[4] * y + h[5]) / w};
    }

    /** Integer motion field from h on every macroblock, then a share of blocks overwritten with junk. */
    private static MotionField field(double[] h, double outlierShare, long seed) {
        int cols = W / Types.MB;
        int rows = H / Types.MB;
        short[] dx = new short[cols * rows];
        short[] dy = new short[cols * rows];
        Random r = new Random(seed);
        for (int i = 0; i < dx.length; i++) {
            double cx = (i % cols) * Types.MB + 8;
            double cy = (i / cols) * Types.MB + 8;
            double[] q = apply(h, cx, cy);
            dx[i] = (short) Math.round(q[0] - cx);
            dy[i] = (short) Math.round(q[1] - cy);
            if (r.nextDouble() < outlierShare) {
                dx[i] = (short) (r.nextInt(41) - 20);
                dy[i] = (short) (r.nextInt(41) - 20);
            }
        }
        return new MotionField(cols, rows, dx, dy);
    }

    private static boolean[] all(MotionField f) {
        boolean[] u = new boolean[f.cols() * f.rows()];
        java.util.Arrays.fill(u, true);
        return u;
    }

    /** Largest error of the fitted camera vector against the true one over all block centers. */
    private static double maxError(Model m, double[] h, MotionField f) {
        double worst = 0;
        double[] v = new double[2];
        for (int i = 0; i < f.cols() * f.rows(); i++) {
            double cx = (i % f.cols()) * Types.MB + 8;
            double cy = (i / f.cols()) * Types.MB + 8;
            double[] q = apply(h, cx, cy);
            m.predict(cx, cy, v);
            worst = Math.max(worst, Math.hypot(v[0] - (q[0] - cx), v[1] - (q[1] - cy)));
        }
        return worst;
    }

    @Test
    void homographyWithThirtyPercentOutliersRecoveredWithinHalfPixel() {
        MotionField f = field(TRUE_H, 0.30, 1);
        Model m = GlobalMotion.fit(f, all(f), W, H, GmConfig.DEFAULT);
        assertEquals(Kind.HOMOGRAPHY, m.kind());
        assertTrue(m.inlierRatio() > 0.65, "inlier ratio " + m.inlierRatio());
        double err = maxError(m, TRUE_H, f);
        assertTrue(err < 0.5, "max error " + err + " px (vectors are rounded to whole pixels)");
    }

    @Test
    void pureTranslationRecovered() {
        double[] t = GlobalMotion.translation(6, -1);
        MotionField f = field(t, 0.2, 2);
        Model m = GlobalMotion.fit(f, all(f), W, H, GmConfig.DEFAULT);
        assertTrue(maxError(m, t, f) < 0.05, "a pan should be recovered almost exactly");
        int[] c = m.centerVector(W, H);
        assertArrayEquals(new int[] {6, -1}, c);
    }

    @Test
    void sameInputGivesIdenticalModel() {
        MotionField f = field(TRUE_H, 0.3, 3);
        Model a = GlobalMotion.fit(f, all(f), W, H, GmConfig.DEFAULT);
        Model b = GlobalMotion.fit(f, all(f), W, H, GmConfig.DEFAULT);
        assertArrayEquals(a.h(), b.h(), 0.0);
        assertEquals(a.inliers(), b.inliers());
    }

    @Test
    void onlyMarkedBlocksAreUsed() {
        // Left half moves with the camera, right half is junk; marking only the left half must give
        // a clean fit even though the junk is half the frame.
        double[] t = GlobalMotion.translation(-5, 0);
        MotionField f = field(t, 0, 4);
        boolean[] use = new boolean[f.cols() * f.rows()];
        for (int i = 0; i < use.length; i++) {
            use[i] = i % f.cols() < f.cols() / 2;
            if (!use[i]) {
                f.dx()[i] = 15;
                f.dy()[i] = 15;
            }
        }
        Model m = GlobalMotion.fit(f, use, W, H, GmConfig.DEFAULT);
        assertEquals(1.0, m.inlierRatio(), 1e-12);
        assertEquals(use.length / 2, m.points());
    }

    @Test
    void collinearPointsFallBackToTranslation() {
        // All points on one row: homography and affine are undetermined, translation is not.
        int n = 40;
        double[] px = new double[n];
        double[] py = new double[n];
        double[] qx = new double[n];
        double[] qy = new double[n];
        for (int i = 0; i < n; i++) {
            px[i] = 8 + 16 * i;
            py[i] = 200;
            qx[i] = px[i] + 4;
            qy[i] = py[i] - 2;
        }
        Model m = GlobalMotion.fitPoints(px, py, qx, qy, W, H, GmConfig.DEFAULT);
        assertEquals(Kind.TRANSLATION, m.kind());
        double[] v = new double[2];
        m.predict(100, 900, v);
        assertEquals(4, v[0], 1e-9);
        assertEquals(-2, v[1], 1e-9);
    }

    @Test
    void tooFewPointsUseMedianAndNoPointsMeanStill() {
        double[] px = {10, 50, 90};
        double[] py = {10, 60, 20};
        double[] qx = {13, 53, 140};
        double[] qy = {10, 60, 20};
        Model m = GlobalMotion.fitPoints(px, py, qx, qy, W, H, GmConfig.DEFAULT);
        assertEquals(Kind.TRANSLATION, m.kind());
        assertEquals(3, m.h()[2], 1e-12);
        Model none = GlobalMotion.fitPoints(new double[0], new double[0], new double[0], new double[0], W, H, GmConfig.DEFAULT);
        assertEquals(Kind.NONE, none.kind());
        assertArrayEquals(GlobalMotion.IDENTITY, none.h(), 0.0);
    }

    @Test
    void fourExactPointsGiveTheExactHomographyAtFullHdScale() {
        int[] idx = {0, 1, 2, 3};
        double[] px = {100, 1800, 1700, 200};
        double[] py = {80, 120, 1000, 950};
        double[] qx = new double[4];
        double[] qy = new double[4];
        for (int i = 0; i < 4; i++) {
            double[] q = apply(TRUE_H, px[i], py[i]);
            qx[i] = q[0];
            qy[i] = q[1];
        }
        double[] h = GlobalMotion.homography(idx, 4, px, py, qx, qy);
        assertNotNull(h);
        assertArrayEquals(TRUE_H, h, 1e-9);
    }

    @Test
    void gaussMatchesKnownSolutionAndDetectsSingular() {
        double[][] a = {{0, 2, 1}, {1, 1, 1}, {2, 1, 0}};
        double[] x = GlobalMotion.gauss(a, new double[] {5, 4, 4});
        assertArrayEquals(new double[] {1, 2, 1}, x, 1e-12);
        double[][] s = {{1, 2}, {2, 4}};
        assertEquals(null, GlobalMotion.gauss(s, new double[] {1, 2}));
    }

    @Test
    void wildModelsAreRejectedAsIllConditioned() {
        assertTrue(GlobalMotion.sane(TRUE_H, W, H, GmConfig.DEFAULT));
        double[] fold = {-1, 0, W, 0, 1, 0, 0, 0, 1};           // mirror image
        double[] huge = GlobalMotion.translation(500, 0);         // 500 px pan in one frame
        double[] behind = {1, 0, 0, 0, 1, 0, -0.001, 0, 1};       // right side maps behind the camera
        assertFalse(GlobalMotion.sane(fold, W, H, GmConfig.DEFAULT));
        assertFalse(GlobalMotion.sane(huge, W, H, GmConfig.DEFAULT));
        assertFalse(GlobalMotion.sane(behind, W, H, GmConfig.DEFAULT));
    }
}
