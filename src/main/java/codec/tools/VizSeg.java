package codec.tools;

import codec.common.ColorConv;
import codec.common.Pad;
import codec.common.Types;
import codec.common.Types.Frame;
import codec.common.Types.Labels;
import codec.common.Types.MotionField;
import codec.common.Types.VideoInfo;
import codec.common.VideoIO;
import codec.encoder.MotionEstimator;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;

/**
 * Pictures of motion and segmentation (B11): overlays at chosen frames.
 *
 * <p>{@code VizSeg <clip> [--at f1,f2,..] [--arrows] [--range R] [--out dir]}. Default frames are
 * 20%, 50% and 80% of the clip. Background blocks are tinted green, each foreground region gets
 * its own color (red, blue, ...), and with {@code --arrows} each macroblock shows its motion
 * vector drawn in the direction the content moved, (-dx, -dy) per 03 D12.
 *
 * <p>Drawing is done directly on the pixel array, so the tool needs nothing beyond ImageIO.
 */
public final class VizSeg {

    /** Region colors: index 0 is background (green), then red, blue, yellow, magenta, cyan, orange. */
    static final int[] COLORS = {0x00C000, 0xFF2020, 0x2060FF, 0xFFE000, 0xFF00FF, 0x00E0E0, 0xFF8000};
    static final int TINT_ALPHA = 90;   // out of 256: strong enough to read, weak enough to see the scene
    static final int ARROW_COLOR = 0xFFFFFF;

    private VizSeg() {
    }

    public static void main(String[] args) throws IOException {
        Options o = Options.parse(args);
        if (o.meCheck) {
            meCheck(o.clip, o.range);
            return;
        }
        Files.createDirectories(o.out);
        String name = o.clip.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        try (VideoIO.Reader reader = VideoIO.open(o.clip)) {
            VideoInfo info = reader.info();
            int[] at = o.at != null ? o.at : defaultFrames(info.frameCount());
            int last = Arrays.stream(at).max().orElse(0);
            int[] prevY = null;
            Frame f = Frame.allocate(info.width(), info.height());
            for (int t = 0; t <= last && reader.read(f); t++) {
                Frame p = Pad.toMacroblocks(f);
                int[] y = ColorConv.luma(p);
                if (prevY != null && contains(at, t)) {
                    long t0 = System.nanoTime();
                    MotionEstimator.Result me = MotionEstimator.fullSearch(y, prevY, p.width(), p.height(), o.range);
                    double ms = (System.nanoTime() - t0) / 1e6;
                    Labels none = new Labels(me.field().cols(), me.field().rows(), new byte[me.field().cols() * me.field().rows()]);
                    int[] rgb = render(p, none, o.arrows ? me.field() : null);
                    Path png = o.out.resolve(String.format("%s_f%04d.png", name, t));
                    write(rgb, p.width(), p.height(), info.width(), info.height(), png);
                    System.out.printf("%s frame %d: ME %.0f ms, %s%n", name, t, ms, png);
                }
                prevY = y;
            }
        }
    }

    /** Block variance at or above this counts as "textured" for the agreement check (std dev 10). */
    static final int CHECK_TEXTURE_VAR = 100;
    static final int CHECK_PAIRS = 5;

    /**
     * P1.3 evidence: hierarchical vs full search on {@link #CHECK_PAIRS} evenly spaced frame pairs.
     * Agreement = both components within 1 px, counted on textured blocks only (flat blocks have
     * no single right answer). Also reports agreement with lambda = 0 and ms per frame for each.
     */
    static void meCheck(Path clip, int range) throws IOException {
        try (VideoIO.Reader reader = VideoIO.open(clip)) {
            VideoInfo info = reader.info();
            int n = Math.max(info.frameCount(), CHECK_PAIRS + 2);
            int[] targets = new int[CHECK_PAIRS];
            for (int k = 0; k < CHECK_PAIRS; k++) {
                targets[k] = 1 + (n - 2) * (k + 1) / (CHECK_PAIRS + 1);
            }
            Frame f = Frame.allocate(info.width(), info.height());
            int[] prevY = null;
            long agree = 0;
            long agree0 = 0;
            long worse = 0;
            long textured = 0;
            double msFull = 0;
            double msHier = 0;
            MotionEstimator.HierParams p0 = new MotionEstimator.HierParams(3, 8, 2, 0);
            for (int t = 0; t <= targets[CHECK_PAIRS - 1] && reader.read(f); t++) {
                Frame p = Pad.toMacroblocks(f);
                int[] y = ColorConv.luma(p);
                if (prevY != null && contains(targets, t)) {
                    int w = p.width();
                    int h = p.height();
                    MotionEstimator.hierarchical(y, prevY, w, h, MotionEstimator.HierParams.DEFAULT, null, 0, 0); // warm-up
                    long t0 = System.nanoTime();
                    MotionEstimator.Result full = MotionEstimator.fullSearch(y, prevY, w, h, range);
                    long t1 = System.nanoTime();
                    MotionEstimator.Result hier = MotionEstimator.hierarchical(y, prevY, w, h, MotionEstimator.HierParams.DEFAULT, null, 0, 0);
                    long t2 = System.nanoTime();
                    MotionEstimator.Result hier0 = MotionEstimator.hierarchical(y, prevY, w, h, p0, null, 0, 0);
                    msFull += (t1 - t0) / 1e6;
                    msHier += (t2 - t1) / 1e6;
                    int cols = w / Types.MB;
                    for (int i = 0; i < cols * (h / Types.MB); i++) {
                        if (blockVariance(y, w, (i % cols) * Types.MB, (i / cols) * Types.MB) < CHECK_TEXTURE_VAR) {
                            continue;
                        }
                        textured++;
                        agree += close(full, hier, i) ? 1 : 0;
                        agree0 += close(full, hier0, i) ? 1 : 0;
                        // "Worse" = hierarchical match error more than 10% above full search's (a real miss,
                        // not an equally good alternative on a repeating texture).
                        if (!close(full, hier, i) && hier.sadBest()[i] * 10L > full.sadBest()[i] * 11L) {
                            worse++;
                        }
                    }
                }
                prevY = y;
            }
            String name = clip.getFileName().toString();
            System.out.printf("%s R=%d pairs=%s textured=%d agree(lambda=4)=%.1f%% agree(lambda=0)=%.1f%% "
                    + "worse-by-10%%=%.1f%% full=%.0f ms/frame hier=%.0f ms/frame%n", name, range, Arrays.toString(targets), textured,
                    100.0 * agree / textured, 100.0 * agree0 / textured, 100.0 * worse / textured, msFull / CHECK_PAIRS, msHier / CHECK_PAIRS);
        }
    }

    private static boolean close(MotionEstimator.Result a, MotionEstimator.Result b, int i) {
        return Math.abs(a.field().dx()[i] - b.field().dx()[i]) <= 1 && Math.abs(a.field().dy()[i] - b.field().dy()[i]) <= 1;
    }

    /** Integer variance of the 16x16 block at (x0, y0). */
    static int blockVariance(int[] y, int w, int x0, int y0) {
        long sum = 0;
        long sq = 0;
        for (int r = 0; r < Types.MB; r++) {
            for (int c = 0; c < Types.MB; c++) {
                int v = y[(y0 + r) * w + x0 + c];
                sum += v;
                sq += (long) v * v;
            }
        }
        int n = Types.MB * Types.MB;
        return (int) ((sq - sum * sum / n) / n);
    }

    static int[] defaultFrames(int frameCount) {
        int n = Math.max(frameCount, 2);
        return new int[] {Math.max(1, n * 20 / 100), n * 50 / 100, n * 80 / 100};
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    /** Packed RGB of the padded frame with label tint and optional arrows. */
    static int[] render(Frame f, Labels labels, MotionField mv) {
        int w = f.width();
        int h = f.height();
        int[] rgb = new int[w * h];
        for (int i = 0; i < rgb.length; i++) {
            int y = i / w;
            int x = i % w;
            int id = labels.get(x / Types.MB, y / Types.MB);
            int c = COLORS[id == 0 ? 0 : 1 + (id - 1) % (COLORS.length - 1)];
            rgb[i] = blend(f.r()[i], f.g()[i], f.b()[i], c);
        }
        if (mv != null) {
            drawArrows(rgb, w, h, mv);
        }
        return rgb;
    }

    static int blend(int r, int g, int b, int c) {
        int a = TINT_ALPHA;
        int rr = (r * (256 - a) + ((c >> 16) & 0xFF) * a) >> 8;
        int gg = (g * (256 - a) + ((c >> 8) & 0xFF) * a) >> 8;
        int bb = (b * (256 - a) + (c & 0xFF) * a) >> 8;
        return (rr << 16) | (gg << 8) | bb;
    }

    /** One line per macroblock from its center along (-dx, -dy), with a 3x3 dot at the start. */
    static void drawArrows(int[] rgb, int w, int h, MotionField mv) {
        for (int by = 0; by < mv.rows(); by++) {
            for (int bx = 0; bx < mv.cols(); bx++) {
                int i = by * mv.cols() + bx;
                int cx = bx * Types.MB + Types.MB / 2;
                int cy = by * Types.MB + Types.MB / 2;
                line(rgb, w, h, cx, cy, cx - mv.dx()[i], cy - mv.dy()[i]);
                for (int oy = -1; oy <= 1; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        put(rgb, w, h, cx + ox, cy + oy, 0x000000);
                    }
                }
            }
        }
    }

    /** Bresenham line. */
    static void line(int[] rgb, int w, int h, int x0, int y0, int x1, int y1) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            put(rgb, w, h, x0, y0, ARROW_COLOR);
            if (x0 == x1 && y0 == y1) {
                return;
            }
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x0 += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    private static void put(int[] rgb, int w, int h, int x, int y, int c) {
        if (x >= 0 && y >= 0 && x < w && y < h) {
            rgb[y * w + x] = c;
        }
    }

    /** Writes the top-left trueW x trueH region (padding cropped). */
    static void write(int[] rgb, int w, int h, int trueW, int trueH, Path png) throws IOException {
        BufferedImage img = new BufferedImage(trueW, trueH, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, trueW, trueH, rgb, 0, w);
        ImageIO.write(img, "png", png.toFile());
    }

    private record Options(Path clip, int[] at, boolean arrows, int range, Path out, boolean meCheck) {
        static Options parse(String[] a) {
            if (a.length < 1) {
                System.err.println("usage: VizSeg <clip> [--at f1,f2,..] [--arrows] [--range R] [--out dir] [--me-check]");
                System.exit(2);
            }
            int[] at = null;
            boolean arrows = false;
            int range = 16;
            Path out = Path.of("out", "viz");
            boolean meCheck = false;
            for (int i = 1; i < a.length; i++) {
                switch (a[i]) {
                    case "--at" -> at = Arrays.stream(a[++i].split(",")).mapToInt(Integer::parseInt).toArray();
                    case "--arrows" -> arrows = true;
                    case "--range" -> range = Integer.parseInt(a[++i]);
                    case "--out" -> out = Path.of(a[++i]);
                    case "--me-check" -> meCheck = true;
                    default -> throw new IllegalArgumentException("unknown option " + a[i]);
                }
            }
            return new Options(Path.of(a[0]), at, arrows, range, out, meCheck);
        }
    }
}
