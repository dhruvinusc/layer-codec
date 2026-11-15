package lab.ec;

import codec.common.ColorConv;
import codec.common.Pad;
import codec.common.Types.Frame;
import codec.common.Types.Labels;
import codec.common.Types.MotionField;
import codec.common.Types.VideoInfo;
import codec.common.VideoIO;
import codec.common.Zio;
import codec.encoder.GlobalMotion;
import codec.encoder.MotionEstimator;
import codec.encoder.Segmenter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * L4 lab, step 1 (prediction error only, no coding). For a few frame pairs per clip, compares how
 * well BACKGROUND macroblocks of frame t are predicted from frame t-1 by:
 * <ul>
 *   <li>ZERO: no motion (floor reference)</li>
 *   <li>MV: the block's own integer motion vector (what mode 2 stores and uses)</li>
 *   <li>HINT: the camera homography evaluated at the block center, rounded to one integer vector
 *       per block (zero side information, integer pixels like MV)</li>
 *   <li>HWARP: the camera homography applied per pixel with bilinear sampling (zero side
 *       information, sub-pixel, follows perspective inside the block)</li>
 * </ul>
 * Labels, vectors and the camera model come from the frozen P1 pipeline, replayed exactly as
 * Segmenter.Run does it (same calls, same seeds), started a few frames before each target so the
 * vector predictors are warm. Only the frozen code is CALLED; nothing in src/ is changed.
 *
 * <p>Measure: SAD on Y over each 16x16 background block, against the previous ORIGINAL frame.
 * Mode 2 predicts from the reconstructed frame; that changes both methods alike, so step 1 uses
 * originals and step 2 measures the real thing.
 *
 * <p>Usage: CamProbe outDir clip1.mp4 [clip2.mp4 ...]
 */
public final class CamProbe {

    /** Target frames at these fractions of the clip (5 pairs per clip). Odd frames = P frames at GOP 2. */
    static final double[] FRACTIONS = {0.10, 0.30, 0.50, 0.70, 0.90};
    /** Frames replayed before each target so prevField and the camera hint are warm (Run state). */
    static final int WARMUP = 6;
    static final int MB = 16;

    private CamProbe() {
    }

    public static void main(String[] args) throws IOException {
        Locale.setDefault(Locale.ROOT);
        Path outDir = Path.of(args[0]);
        Files.createDirectories(outDir);
        try (PrintWriter blocks = new PrintWriter(Files.newBufferedWriter(outDir.resolve("step1_blocks.csv")));
             PrintWriter frames = new PrintWriter(Files.newBufferedWriter(outDir.resolve("step1_frames.csv")))) {
            blocks.println("clip,frame,mb,reliable,sad_zero,sad_mv,sad_hint,sad_hwarp,mvdx,mvdy,camdx,camdy");
            frames.println("clip,frame,camera,inlier_ratio,rms_px,bg_blocks,fg_blocks,mean_sad_zero,mean_sad_mv,"
                    + "mean_sad_hint,mean_sad_hwarp,hwarp_beats_mv_pct,mv_bytes_zlib_all,mv_bytes_zlib_fg_only");
            for (int i = 1; i < args.length; i++) {
                probeClip(Path.of(args[i]), blocks, frames);
            }
        }
    }

    /** Replay of Segmenter.Run for one target frame. */
    static final class Chain {
        final int target;
        final int start;
        int[] prevY;
        MotionField prevField;
        int[] hint = {0, 0};
        final Segmenter.Temporal temporal = new Segmenter.Temporal();
        // stashed for the target pair
        int[] yT;
        int[] yPrev;
        MotionEstimator.Result meT;
        GlobalMotion.Model camT;
        boolean[] relT;
        Labels labelsT;
        int pushedAfterTarget = -1;

        Chain(int target) {
            this.target = target;
            this.start = Math.max(0, target - WARMUP);
        }
    }

    static void probeClip(Path clip, PrintWriter blocks, PrintWriter frames) throws IOException {
        String name = clip.getFileName().toString().replaceFirst("\\.mp4$", "");
        try (VideoIO.Reader in = VideoIO.open(clip)) {
            VideoInfo info = in.info();
            int count = info.frameCount();
            int w = info.paddedW();
            int h = info.paddedH();
            Chain[] chains = new Chain[FRACTIONS.length];
            for (int k = 0; k < chains.length; k++) {
                int t = (int) Math.round(FRACTIONS[k] * (count - 1));
                chains[k] = new Chain(t | 1); // odd: a P frame at GOP 2
            }
            Frame f = Frame.allocate(info.width(), info.height());
            int idx = -1;
            int c = 0;
            while (c < chains.length && in.read(f)) {
                idx++;
                Chain ch = chains[c];
                if (idx < ch.start) {
                    continue;
                }
                int[] y = ColorConv.luma(Pad.toMacroblocks(f));
                Labels fin = step(ch, y, w, h, idx);
                if (idx == ch.target + 1) {
                    ch.labelsT = fin; // temporal vote emits frame t when t+1 is pushed
                    report(name, ch, w, h, blocks, frames);
                    c++;
                }
            }
            if (c < chains.length) {
                throw new IOException(name + ": clip ended before target " + chains[c].target);
            }
        }
    }

    /** One Run.push, verbatim in its calls and their order. */
    static Labels step(Chain ch, int[] y, int w, int h, int idx) {
        Segmenter.SegConfig sc = Segmenter.SegConfig.DEFAULT;
        Labels fin = null;
        if (ch.prevY != null) {
            MotionEstimator.Result me = MotionEstimator.hierarchical(y, ch.prevY, w, h,
                    MotionEstimator.HierParams.DEFAULT, ch.prevField, ch.hint[0], ch.hint[1]);
            boolean[] rel = Segmenter.reliable(y, w, h, me, sc);
            GlobalMotion.Model cam = GlobalMotion.fit(me.field(), rel, w, h, GlobalMotion.GmConfig.DEFAULT);
            Labels raw = Segmenter.fill(Segmenter.group(me.field(),
                    Segmenter.foregroundEligible(y, ch.prevY, w, h, me, cam, sc), cam, sc), sc);
            fin = ch.temporal.push(raw, me.field());
            if (idx == ch.target) {
                ch.yT = y;
                ch.yPrev = ch.prevY;
                ch.meT = me;
                ch.camT = cam;
                ch.relT = rel;
            }
            ch.prevField = me.field();
            ch.hint = cam.centerVector(w, h);
        }
        ch.prevY = y;
        return fin;
    }

    static void report(String clip, Chain ch, int w, int h, PrintWriter blocks, PrintWriter frames) {
        MotionField mv = ch.meT.field();
        int cols = w / MB;
        int n = cols * (h / MB);
        long sz = 0, smv = 0, shi = 0, shw = 0;
        int bg = 0, wins = 0;
        double[] v = new double[2];
        byte[] mvAll = new byte[2 * n];
        byte[] mvFg = new byte[2 * n];
        for (int i = 0; i < n; i++) {
            mvAll[2 * i] = (byte) mv.dx()[i];
            mvAll[2 * i + 1] = (byte) mv.dy()[i];
            boolean isBg = (ch.labelsT.id()[i] & 0xFF) == 0;
            if (!isBg) {
                mvFg[2 * i] = mvAll[2 * i];
                mvFg[2 * i + 1] = mvAll[2 * i + 1];
                continue;
            }
            int x0 = (i % cols) * MB;
            int y0 = (i / cols) * MB;
            ch.camT.predict(x0 + MB / 2.0, y0 + MB / 2.0, v);
            int hdx = (int) Math.round(v[0]);
            int hdy = (int) Math.round(v[1]);
            int a = sadInt(ch.yT, ch.yPrev, w, h, x0, y0, 0, 0);
            int b = sadInt(ch.yT, ch.yPrev, w, h, x0, y0, mv.dx()[i], mv.dy()[i]);
            int d = sadInt(ch.yT, ch.yPrev, w, h, x0, y0, hdx, hdy);
            double e = sadWarp(ch.yT, ch.yPrev, w, h, x0, y0, ch.camT.h());
            blocks.printf("%s,%d,%d,%d,%d,%d,%d,%.1f,%d,%d,%.2f,%.2f%n", clip, ch.target, i, ch.relT[i] ? 1 : 0,
                    a, b, d, e, mv.dx()[i], mv.dy()[i], v[0], v[1]);
            sz += a;
            smv += b;
            shi += d;
            shw += Math.round(e);
            bg++;
            wins += e < b ? 1 : 0;
        }
        int zAll = Zio.deflate(mvAll, 0, mvAll.length).length;
        int zFg = Zio.deflate(mvFg, 0, mvFg.length).length;
        double k = Math.max(1, bg);
        frames.printf("%s,%d,%s,%.3f,%.2f,%d,%d,%.1f,%.1f,%.1f,%.1f,%.1f,%d,%d%n", clip, ch.target, ch.camT.kind(),
                ch.camT.inlierRatio(), ch.camT.rmsInlierPx(), bg, n - bg, sz / k, smv / k, shi / k, shw / k,
                100.0 * wins / k, zAll, zFg);
        frames.flush();
        blocks.flush();
        System.out.printf("%s f%d %s inl %.2f  bg %d  SAD zero %.0f  mv %.0f  hint %.0f  hwarp %.0f  "
                + "hwarp<mv %.0f%%  MV zlib %d -> %d B%n", clip, ch.target, ch.camT.kind(), ch.camT.inlierRatio(), bg,
                sz / k, smv / k, shi / k, shw / k, 100.0 * wins / k, zAll, zFg);
    }

    /** SAD of a 16x16 block against the integer-shifted reference, coordinates clamped like McCodec. */
    static int sadInt(int[] cur, int[] ref, int w, int h, int x0, int y0, int dx, int dy) {
        int s = 0;
        for (int yy = 0; yy < MB; yy++) {
            int ry = clamp(y0 + yy + dy, h);
            for (int xx = 0; xx < MB; xx++) {
                int rx = clamp(x0 + xx + dx, w);
                s += Math.abs(cur[(y0 + yy) * w + x0 + xx] - ref[ry * w + rx]);
            }
        }
        return s;
    }

    /**
     * SAD of a 16x16 block against the homography-warped reference. Pixel i covers [i, i+1], so its
     * center is i + 0.5; that is the coordinate system the fit used (block center = x0 + 8).
     */
    static double sadWarp(int[] cur, int[] ref, int w, int h, int x0, int y0, double[] m) {
        double s = 0;
        for (int yy = 0; yy < MB; yy++) {
            for (int xx = 0; xx < MB; xx++) {
                double x = x0 + xx + 0.5;
                double y = y0 + yy + 0.5;
                double q = m[6] * x + m[7] * y + m[8];
                double sx = (m[0] * x + m[1] * y + m[2]) / q - 0.5;
                double sy = (m[3] * x + m[4] * y + m[5]) / q - 0.5;
                s += Math.abs(cur[(y0 + yy) * w + x0 + xx] - bilinear(ref, w, h, sx, sy));
            }
        }
        return s;
    }

    static double bilinear(int[] p, int w, int h, double x, double y) {
        x = Math.max(0, Math.min(w - 1, x));
        y = Math.max(0, Math.min(h - 1, y));
        int ix = Math.min((int) x, w - 2);
        int iy = Math.min((int) y, h - 2);
        double fx = x - ix;
        double fy = y - iy;
        double top = p[iy * w + ix] * (1 - fx) + p[iy * w + ix + 1] * fx;
        double bot = p[(iy + 1) * w + ix] * (1 - fx) + p[(iy + 1) * w + ix + 1] * fx;
        return top * (1 - fy) + bot * fy;
    }

    static int clamp(int v, int n) {
        return v < 0 ? 0 : v >= n ? n - 1 : v;
    }
}
