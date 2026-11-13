package codec.encoder;

import codec.common.Types;
import codec.common.Types.MotionField;
import java.util.Arrays;
import java.util.Random;

/**
 * Camera motion between two frames (03 D3 step 2), fitted to the block motion vectors.
 *
 * <p>Every reliable block gives one correspondence: its center p in frame t matches p + mv in frame
 * t-1 (03 D12). A homography (8 numbers) maps one to the other exactly for a flat scene or a camera
 * that only rotates; an affine model (6) or a pure translation (2) is the fallback. Moving people are
 * outliers, so the fit uses RANSAC: fit the model to a random minimal sample, count how many blocks
 * agree within {@link GmConfig#inlierPx}, keep the best, then refit by least squares on its inliers.
 *
 * <p>Deterministic: a fixed seed and a fixed number of iterations give the same model for the same
 * input. Everything is hand-written (Gaussian elimination with partial pivoting, doubles).
 */
public final class GlobalMotion {

    /** Which model was accepted. NONE means no usable vectors: the camera is assumed still. */
    public enum Kind { HOMOGRAPHY, AFFINE, TRANSLATION, NONE }

    /**
     * Camera-motion settings.
     *
     * @param iterations       RANSAC samples per model (fixed, for determinism)
     * @param inlierPx         a block agrees with the model if its vector is within this distance
     * @param minInlierRatio   below this share of agreeing blocks the model is rejected and the next
     *                         simpler one is tried
     * @param minPoints        fewer reliable blocks than this: use the median translation directly
     * @param maxCornerShift   a model that moves a frame corner further than this share of the larger
     *                         frame side is treated as ill-conditioned (a wild perspective fit)
     * @param seed             RANSAC random seed
     */
    public record GmConfig(int iterations, double inlierPx, double minInlierRatio, int minPoints,
                           double maxCornerShift, long seed) {
        public static final GmConfig DEFAULT = new GmConfig(500, 2.5, 0.3, 8, 0.2, 576L);
    }

    /**
     * A fitted camera model.
     *
     * @param kind        accepted model type
     * @param h           3x3 matrix, row-major, h[8] = 1, mapping (x, y) in frame t to frame t-1
     * @param inliers     number of correspondences within the inlier distance
     * @param points      number of correspondences used
     * @param rmsInlierPx root mean square error over the inliers, in pixels
     */
    public record Model(Kind kind, double[] h, int inliers, int points, double rmsInlierPx) {
        public double inlierRatio() {
            return points == 0 ? 0 : (double) inliers / points;
        }

        /** Camera motion vector at (x, y) in the D12 convention: out = {x' - x, y' - y}. */
        public void predict(double x, double y, double[] out) {
            double w = h[6] * x + h[7] * y + h[8];
            out[0] = (h[0] * x + h[1] * y + h[2]) / w - x;
            out[1] = (h[3] * x + h[4] * y + h[5]) / w - y;
        }

        /** Camera vector at the frame center, rounded: the search hint for the next frame pair. */
        public int[] centerVector(int width, int height) {
            double[] v = new double[2];
            predict(width / 2.0, height / 2.0, v);
            return new int[] {(int) Math.round(v[0]), (int) Math.round(v[1])};
        }
    }

    static final double[] IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    /** Twice the triangle area (px^2) under which three sample points count as collinear. */
    static final double MIN_CROSS = 100;
    /** Pivots smaller than this (in normalized coordinates) mean a singular system. */
    static final double PIVOT_EPS = 1e-9;

    private GlobalMotion() {
    }

    /** Fits the camera model to the vectors of the blocks marked {@code use}. */
    public static Model fit(MotionField mv, boolean[] use, int width, int height, GmConfig c) {
        int n = 0;
        for (boolean u : use) {
            n += u ? 1 : 0;
        }
        double[] px = new double[n];
        double[] py = new double[n];
        double[] qx = new double[n];
        double[] qy = new double[n];
        int k = 0;
        for (int i = 0; i < use.length; i++) {
            if (use[i]) {
                px[k] = (i % mv.cols()) * Types.MB + Types.MB / 2.0;
                py[k] = (i / mv.cols()) * Types.MB + Types.MB / 2.0;
                qx[k] = px[k] + mv.dx()[i];
                qy[k] = py[k] + mv.dy()[i];
                k++;
            }
        }
        return fitPoints(px, py, qx, qy, width, height, c);
    }

    /** Fits p -> q with the fallback chain homography, affine, translation. */
    public static Model fitPoints(double[] px, double[] py, double[] qx, double[] qy,
                                  int width, int height, GmConfig c) {
        int n = px.length;
        if (n == 0) {
            return new Model(Kind.NONE, IDENTITY.clone(), 0, 0, 0);
        }
        if (n < c.minPoints()) {
            double[] h = translation(median(qx, px), median(qy, py));
            return score(Kind.TRANSLATION, h, px, py, qx, qy, c);
        }
        Model hom = ransac(Kind.HOMOGRAPHY, 4, px, py, qx, qy, width, height, c);
        if (hom != null && hom.inlierRatio() >= c.minInlierRatio()) {
            return hom;
        }
        Model aff = ransac(Kind.AFFINE, 3, px, py, qx, qy, width, height, c);
        if (aff != null && aff.inlierRatio() >= c.minInlierRatio()) {
            return aff;
        }
        Model tr = ransac(Kind.TRANSLATION, 1, px, py, qx, qy, width, height, c);
        return tr != null ? tr : score(Kind.TRANSLATION, translation(median(qx, px), median(qy, py)), px, py, qx, qy, c);
    }

    /** RANSAC with a fixed seed, then two rounds of least-squares refit on the inliers. */
    static Model ransac(Kind kind, int sampleSize, double[] px, double[] py, double[] qx, double[] qy,
                        int width, int height, GmConfig c) {
        int n = px.length;
        Random rnd = new Random(c.seed());
        int[] sample = new int[sampleSize];
        double thr2 = c.inlierPx() * c.inlierPx();
        double[] best = null;
        int bestCount = -1;
        double bestErr = Double.MAX_VALUE;
        for (int it = 0; it < c.iterations(); it++) {
            pick(rnd, n, sample);
            if (degenerate(kind, sample, px, py, qx, qy)) {
                continue;
            }
            double[] h = solve(kind, sample, sampleSize, px, py, qx, qy);
            if (h == null || !sane(h, width, height, c)) {
                continue;
            }
            int count = 0;
            double err = 0;
            for (int i = 0; i < n; i++) {
                double e = err2(h, px[i], py[i], qx[i], qy[i]);
                if (e <= thr2) {
                    count++;
                    err += e;
                }
            }
            if (count > bestCount || (count == bestCount && err < bestErr)) {
                best = h;
                bestCount = count;
                bestErr = err;
            }
        }
        if (best == null) {
            return null;
        }
        for (int round = 0; round < 2; round++) {
            int[] in = inliers(best, px, py, qx, qy, thr2);
            if (in.length < sampleSize) {
                break;
            }
            double[] h = solve(kind, in, in.length, px, py, qx, qy);
            if (h == null || !sane(h, width, height, c)) {
                break;
            }
            best = h;
        }
        return score(kind, best, px, py, qx, qy, c);
    }

    /** Distinct random indices in [0, n). */
    private static void pick(Random rnd, int n, int[] out) {
        for (int k = 0; k < out.length; k++) {
            boolean again;
            do {
                out[k] = rnd.nextInt(n);
                again = false;
                for (int j = 0; j < k; j++) {
                    again |= out[j] == out[k];
                }
            } while (again);
        }
    }

    /** Collinear samples give a singular or wild fit; skip them before solving. */
    static boolean degenerate(Kind kind, int[] s, double[] px, double[] py, double[] qx, double[] qy) {
        if (kind == Kind.TRANSLATION) {
            return false;
        }
        for (int a = 0; a < s.length; a++) {
            for (int b = a + 1; b < s.length; b++) {
                for (int d = b + 1; d < s.length; d++) {
                    if (Math.abs(cross(px, py, s[a], s[b], s[d])) < MIN_CROSS
                            || Math.abs(cross(qx, qy, s[a], s[b], s[d])) < MIN_CROSS) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static double cross(double[] x, double[] y, int a, int b, int d) {
        return (x[b] - x[a]) * (y[d] - y[a]) - (y[b] - y[a]) * (x[d] - x[a]);
    }

    /** Least-squares model from the listed correspondences (exact when the list is minimal). */
    static double[] solve(Kind kind, int[] idx, int m, double[] px, double[] py, double[] qx, double[] qy) {
        return switch (kind) {
            case HOMOGRAPHY -> homography(idx, m, px, py, qx, qy);
            case AFFINE -> affine(idx, m, px, py, qx, qy);
            default -> {
                double sx = 0;
                double sy = 0;
                for (int k = 0; k < m; k++) {
                    sx += qx[idx[k]] - px[idx[k]];
                    sy += qy[idx[k]] - py[idx[k]];
                }
                yield translation(sx / m, sy / m);
            }
        };
    }

    /**
     * Homography with h33 = 1 by the linear (DLT) equations, in Hartley-normalized coordinates:
     * each point set is shifted to its centroid and scaled to mean distance sqrt(2), so the 8x8
     * system is well conditioned even with 1920-pixel coordinates.
     */
    static double[] homography(int[] idx, int m, double[] px, double[] py, double[] qx, double[] qy) {
        double[] t1 = normalizer(idx, m, px, py);
        double[] t2 = normalizer(idx, m, qx, qy);
        double[][] ata = new double[8][8];
        double[] atb = new double[8];
        double[] row = new double[8];
        for (int k = 0; k < m; k++) {
            int i = idx[k];
            double x = t1[0] * px[i] + t1[1];
            double y = t1[0] * py[i] + t1[2];
            double u = t2[0] * qx[i] + t2[1];
            double v = t2[0] * qy[i] + t2[2];
            fill(row, x, y, 1, 0, 0, 0, -x * u, -y * u);
            accumulate(ata, atb, row, u);
            fill(row, 0, 0, 0, x, y, 1, -x * v, -y * v);
            accumulate(ata, atb, row, v);
        }
        double[] hn = gauss(ata, atb);
        if (hn == null) {
            return null;
        }
        // H = T2^-1 * Hn * T1, then scale so h33 = 1.
        double[] n3 = {hn[0], hn[1], hn[2], hn[3], hn[4], hn[5], hn[6], hn[7], 1};
        double[] a = {t1[0], 0, t1[1], 0, t1[0], t1[2], 0, 0, 1};
        double[] b = {1 / t2[0], 0, -t2[1] / t2[0], 0, 1 / t2[0], -t2[2] / t2[0], 0, 0, 1};
        double[] h = mul(b, mul(n3, a));
        if (Math.abs(h[8]) < PIVOT_EPS) {
            return null;
        }
        for (int k = 0; k < 9; k++) {
            h[k] /= h[8];
        }
        return h;
    }

    /** Affine model: two independent 3-unknown least-squares problems (x' and y'). */
    static double[] affine(int[] idx, int m, double[] px, double[] py, double[] qx, double[] qy) {
        double[] t1 = normalizer(idx, m, px, py);
        double[][] ata = new double[3][3];
        double[] bx = new double[3];
        double[] by = new double[3];
        double[] row = new double[3];
        for (int k = 0; k < m; k++) {
            int i = idx[k];
            row[0] = t1[0] * px[i] + t1[1];
            row[1] = t1[0] * py[i] + t1[2];
            row[2] = 1;
            for (int r = 0; r < 3; r++) {
                for (int s = 0; s < 3; s++) {
                    ata[r][s] += row[r] * row[s];
                }
                bx[r] += row[r] * qx[i];
                by[r] += row[r] * qy[i];
            }
        }
        double[][] copy = new double[3][];
        for (int r = 0; r < 3; r++) {
            copy[r] = ata[r].clone();
        }
        double[] ax = gauss(ata, bx);
        double[] ay = gauss(copy, by);
        if (ax == null || ay == null) {
            return null;
        }
        double[] n3 = {ax[0], ax[1], ax[2], ay[0], ay[1], ay[2], 0, 0, 1};
        double[] a = {t1[0], 0, t1[1], 0, t1[0], t1[2], 0, 0, 1};
        return mul(n3, a);
    }

    static double[] translation(double tx, double ty) {
        return new double[] {1, 0, tx, 0, 1, ty, 0, 0, 1};
    }

    /** {s, -s*mx, -s*my}: shift to the centroid, scale to mean distance sqrt(2). */
    private static double[] normalizer(int[] idx, int m, double[] x, double[] y) {
        double mx = 0;
        double my = 0;
        for (int k = 0; k < m; k++) {
            mx += x[idx[k]];
            my += y[idx[k]];
        }
        mx /= m;
        my /= m;
        double d = 0;
        for (int k = 0; k < m; k++) {
            d += Math.hypot(x[idx[k]] - mx, y[idx[k]] - my);
        }
        d /= m;
        double s = d > 0 ? Math.sqrt(2) / d : 1;
        return new double[] {s, -s * mx, -s * my};
    }

    private static void fill(double[] r, double... v) {
        System.arraycopy(v, 0, r, 0, r.length);
    }

    private static void accumulate(double[][] ata, double[] atb, double[] row, double rhs) {
        for (int r = 0; r < row.length; r++) {
            for (int s = 0; s < row.length; s++) {
                ata[r][s] += row[r] * row[s];
            }
            atb[r] += row[r] * rhs;
        }
    }

    /** Gaussian elimination with partial pivoting; null if singular. Overwrites its inputs. */
    static double[] gauss(double[][] a, double[] b) {
        int n = b.length;
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[piv][col])) {
                    piv = r;
                }
            }
            if (Math.abs(a[piv][col]) < PIVOT_EPS) {
                return null;
            }
            double[] tr = a[col];
            a[col] = a[piv];
            a[piv] = tr;
            double tb = b[col];
            b[col] = b[piv];
            b[piv] = tb;
            for (int r = col + 1; r < n; r++) {
                double f = a[r][col] / a[col][col];
                for (int s = col; s < n; s++) {
                    a[r][s] -= f * a[col][s];
                }
                b[r] -= f * b[col];
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double s = b[r];
            for (int k = r + 1; k < n; k++) {
                s -= a[r][k] * x[k];
            }
            x[r] = s / a[r][r];
        }
        return x;
    }

    private static double[] mul(double[] a, double[] b) {
        double[] c = new double[9];
        for (int r = 0; r < 3; r++) {
            for (int s = 0; s < 3; s++) {
                c[r * 3 + s] = a[r * 3] * b[s] + a[r * 3 + 1] * b[3 + s] + a[r * 3 + 2] * b[6 + s];
            }
        }
        return c;
    }

    /** Squared pixel error of p -> q under h; points mapped behind the camera are infinitely bad. */
    static double err2(double[] h, double x, double y, double u, double v) {
        double w = h[6] * x + h[7] * y + h[8];
        if (w <= 1e-6) {
            return Double.MAX_VALUE;
        }
        double ex = (h[0] * x + h[1] * y + h[2]) / w - u;
        double ey = (h[3] * x + h[4] * y + h[5]) / w - v;
        return ex * ex + ey * ey;
    }

    /** Rejects fits that fold the frame or throw a corner further than maxCornerShift allows. */
    static boolean sane(double[] h, int width, int height, GmConfig c) {
        double limit = c.maxCornerShift() * Math.max(width, height);
        double[][] corners = {{0, 0}, {width, 0}, {0, height}, {width, height}};
        for (double[] p : corners) {
            double w = h[6] * p[0] + h[7] * p[1] + h[8];
            if (w <= 1e-6) {
                return false;
            }
            double x = (h[0] * p[0] + h[1] * p[1] + h[2]) / w;
            double y = (h[3] * p[0] + h[4] * p[1] + h[5]) / w;
            if (Math.hypot(x - p[0], y - p[1]) > limit) {
                return false;
            }
        }
        return h[0] * h[4] - h[1] * h[3] > 0;
    }

    private static int[] inliers(double[] h, double[] px, double[] py, double[] qx, double[] qy, double thr2) {
        int[] tmp = new int[px.length];
        int k = 0;
        for (int i = 0; i < px.length; i++) {
            if (err2(h, px[i], py[i], qx[i], qy[i]) <= thr2) {
                tmp[k++] = i;
            }
        }
        return Arrays.copyOf(tmp, k);
    }

    private static Model score(Kind kind, double[] h, double[] px, double[] py, double[] qx, double[] qy, GmConfig c) {
        double thr2 = c.inlierPx() * c.inlierPx();
        int count = 0;
        double err = 0;
        for (int i = 0; i < px.length; i++) {
            double e = err2(h, px[i], py[i], qx[i], qy[i]);
            if (e <= thr2) {
                count++;
                err += e;
            }
        }
        return new Model(kind, h, count, px.length, count == 0 ? 0 : Math.sqrt(err / count));
    }

    /** Median of q - p. */
    private static double median(double[] q, double[] p) {
        double[] d = new double[q.length];
        for (int i = 0; i < d.length; i++) {
            d[i] = q[i] - p[i];
        }
        Arrays.sort(d);
        int m = d.length / 2;
        return d.length % 2 == 1 ? d[m] : (d[m - 1] + d[m]) / 2;
    }
}
