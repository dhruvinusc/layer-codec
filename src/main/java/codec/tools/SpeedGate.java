package codec.tools;

import codec.common.ColorConv;
import codec.common.Display;
import codec.common.Pad;
import codec.common.Types.Frame;
import codec.common.Types.VideoInfo;
import codec.common.VideoIO;
import java.awt.GraphicsEnvironment;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * P0.8 feasibility numbers, THROWAWAY: proves Java is fast enough before P1 starts.
 * Not graded code. The real DCT and file format belong to Window B (P2).
 *
 * <p>{@code SpeedGate <SAL.mp4>}: (a) display at 30 fps if a screen exists, (b) 16x16 SAD,
 * (c) naive separable 8x8 IDCT over one padded 1080p frame x 3 channels, (d) inflate of one
 * frame of realistic quantized coefficients. Every timing is taken after JIT warm-up.
 */
public final class SpeedGate {

    private static final int N = 8;
    private static final double[][] COS = new double[N][N];

    static {
        for (int u = 0; u < N; u++) {
            double cu = u == 0 ? Math.sqrt(0.5) : 1.0;
            for (int x = 0; x < N; x++) {
                COS[u][x] = 0.5 * cu * Math.cos((2 * x + 1) * u * Math.PI / 16);
            }
        }
    }

    private SpeedGate() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: SpeedGate <SAL.mp4>");
            System.exit(2);
        }
        List<Frame> frames = load(Path.of(args[0]), 100, 30);
        System.out.printf("cores %d, max heap %d MB%n", Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() >> 20);
        sad(frames);
        Frame padded = Pad.toMacroblocks(frames.get(0));
        short[][] coeffs = idct(padded);
        inflate(coeffs, padded);
        display(frames);
        System.exit(0);
    }

    private static List<Frame> load(Path clip, int skip, int count) throws IOException {
        List<Frame> out = new ArrayList<>();
        try (VideoIO.Reader r = VideoIO.open(clip)) {
            VideoInfo info = r.info();
            Frame scratch = Frame.allocate(info.width(), info.height());
            for (int i = 0; i < skip && r.read(scratch); i++) {
                // Skip into the clip where there is motion.
            }
            Frame f;
            while (out.size() < count && (f = r.read()) != null) {
                out.add(f);
            }
        }
        return out;
    }

    /** (b) Cost of one 16x16 SAD, the inner step of block matching. */
    private static void sad(List<Frame> frames) {
        Frame a = frames.get(0);
        Frame b = frames.get(1);
        int w = a.width();
        int h = a.height();
        int[] ya = ColorConv.luma(a);
        int[] yb = ColorConv.luma(b);
        Random rnd = new Random(576);
        int n = 2_000_000;
        int[] pa = new int[n];
        int[] pb = new int[n];
        for (int i = 0; i < n; i++) {
            pa[i] = rnd.nextInt(h - 16) * w + rnd.nextInt(w - 16);
            pb[i] = rnd.nextInt(h - 16) * w + rnd.nextInt(w - 16);
        }
        long sink = 0;
        for (int rep = 0; rep < 3; rep++) {
            for (int i = 0; i < n; i++) {
                sink += sad16(ya, yb, w, pa[i], pb[i]);
            }
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            sink += sad16(ya, yb, w, pa[i], pb[i]);
        }
        double ns = (System.nanoTime() - t0) / (double) n;
        System.out.printf("(b) 16x16 SAD: %.1f ns each (checksum %d)%n", ns, sink % 1000);
        System.out.printf("    full search +/-16 on 1080p, 1 thread: %.0f ms/frame; +/-32: %.0f ms/frame%n",
                ns * 8160 * 33 * 33 / 1e6, ns * 8160 * 65 * 65 / 1e6);
    }

    static int sad16(int[] a, int[] b, int w, int oa, int ob) {
        int s = 0;
        for (int y = 0; y < 16; y++) {
            int ia = oa + y * w;
            int ib = ob + y * w;
            for (int x = 0; x < 16; x++) {
                s += Math.abs(a[ia + x] - b[ib + x]);
            }
        }
        return s;
    }

    /** (c) Naive separable IDCT of every 8x8 block, 3 channels. Returns quantized coefficients for (d). */
    private static short[][] idct(Frame f) {
        int w = f.width();
        int h = f.height();
        int blocks = (w / N) * (h / N);
        int[][] planes = {f.r(), f.g(), f.b()};
        short[][] coeffs = new short[3][blocks * 64];
        for (int c = 0; c < 3; c++) {
            final int ch = c;
            IntStream.range(0, blocks).parallel().forEach(k -> forwardQuant(planes[ch], w, k, coeffs[ch], k % 7 == 0 ? 2 : 5));
        }
        double[][] out = new double[3][w * h];
        for (int rep = 0; rep < 5; rep++) {
            idctAll(coeffs, out, w, blocks, false);
            idctAll(coeffs, out, w, blocks, true);
        }
        long t0 = System.nanoTime();
        for (int rep = 0; rep < 5; rep++) {
            idctAll(coeffs, out, w, blocks, false);
        }
        double single = (System.nanoTime() - t0) / 5e6;
        t0 = System.nanoTime();
        for (int rep = 0; rep < 20; rep++) {
            idctAll(coeffs, out, w, blocks, true);
        }
        double multi = (System.nanoTime() - t0) / 20e6;
        System.out.printf("(c) naive IDCT %dx%d x 3 channels (%d blocks): 1 thread %.1f ms, all cores %.1f ms "
                + "(target < 25 ms all cores)%n", w, h, blocks * 3, single, multi);
        return coeffs;
    }

    private static void idctAll(short[][] coeffs, double[][] out, int w, int blocks, boolean parallel) {
        for (int c = 0; c < 3; c++) {
            final int ch = c;
            IntStream range = IntStream.range(0, blocks);
            (parallel ? range.parallel() : range).forEach(k -> idctBlock(coeffs[ch], out[ch], w, k));
        }
    }

    private static void idctBlock(short[] coef, double[] out, int w, int k) {
        int base = k * 64;
        double[] tmp = new double[64];
        for (int u = 0; u < N; u++) {
            for (int x = 0; x < N; x++) {
                double s = 0;
                for (int v = 0; v < N; v++) {
                    s += COS[v][x] * coef[base + u * 8 + v];
                }
                tmp[u * 8 + x] = s;
            }
        }
        int bx = (k % (w / N)) * N;
        int by = (k / (w / N)) * N;
        for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
                double s = 0;
                for (int u = 0; u < N; u++) {
                    s += COS[u][y] * tmp[u * 8 + x];
                }
                out[(by + y) * w + bx + x] = s + 128;
            }
        }
    }

    private static void forwardQuant(int[] plane, int w, int k, short[] coef, int n) {
        int bx = (k % (w / N)) * N;
        int by = (k / (w / N)) * N;
        double step = 1 << n;
        for (int u = 0; u < N; u++) {
            for (int v = 0; v < N; v++) {
                double s = 0;
                for (int y = 0; y < N; y++) {
                    for (int x = 0; x < N; x++) {
                        s += COS[u][y] * COS[v][x] * (plane[(by + y) * w + bx + x] - 128);
                    }
                }
                coef[k * 64 + u * 8 + v] = (short) Math.round(s / step);
            }
        }
    }

    /** (d) One frame in the D7 layout (type byte + 3 x 64 int16 per block), deflated, then inflated. */
    private static void inflate(short[][] coeffs, Frame f) throws IOException, DataFormatException {
        int blocks = coeffs[0].length / 64;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(blocks * 385);
        try (DataOutputStream out = new DataOutputStream(bos)) {
            for (int k = 0; k < blocks; k++) {
                out.writeByte(k % 7 == 0 ? 1 : 0);
                for (int c = 0; c < 3; c++) {
                    for (int i = 0; i < 64; i++) {
                        out.writeShort(coeffs[c][k * 64 + i]);
                    }
                }
            }
        }
        byte[] raw = bos.toByteArray();
        Deflater def = new Deflater(1);
        def.setInput(raw);
        def.finish();
        byte[] comp = new byte[raw.length + 1024];
        long t0 = System.nanoTime();
        int clen = def.deflate(comp);
        double deflateMs = (System.nanoTime() - t0) / 1e6;
        def.end();
        byte[] back = new byte[raw.length];
        for (int rep = 0; rep < 10; rep++) {
            inflateOnce(comp, clen, back);
        }
        t0 = System.nanoTime();
        for (int rep = 0; rep < 20; rep++) {
            inflateOnce(comp, clen, back);
        }
        double inflateMs = (System.nanoTime() - t0) / 20e6;
        System.out.printf("(d) frame %dx%d raw %.2f MB -> zlib-1 %.2f MB (ratio %.1fx); deflate %.1f ms, "
                + "inflate %.1f ms%n", f.width(), f.height(), raw.length / 1e6, clen / 1e6,
                (double) raw.length / clen, deflateMs, inflateMs);
    }

    private static void inflateOnce(byte[] comp, int clen, byte[] back) throws DataFormatException {
        Inflater inf = new Inflater();
        inf.setInput(comp, 0, clen);
        int n = inf.inflate(back);
        inf.end();
        if (n != back.length) {
            throw new IllegalStateException("inflate size mismatch");
        }
    }

    /** (a) 300 shows of pre-loaded 1080p frames on the steady clock; counts late frames. */
    private static void display(List<Frame> frames) {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("(a) display: skipped, no screen (run on the laptop)");
            return;
        }
        Frame first = frames.get(0);
        Display d = new Display("SpeedGate", first.width(), first.height());
        codec.common.Clock clock = new codec.common.Clock(30, 1);
        for (int i = 0; i < 30; i++) {
            d.show(frames.get(i % frames.size()));
        }
        int late = 0;
        int shows = 300;
        clock.start(0);
        for (int i = 0; i < shows; i++) {
            long wait = clock.nanosUntil(i);
            if (wait > 0) {
                java.util.concurrent.locks.LockSupport.parkNanos(wait);
            } else if (-wait > clock.periodNanos() / 2) {
                late++;
            }
            d.show(frames.get(i % frames.size()));
        }
        d.close();
        System.out.printf("(a) display 300 x 1080p at 30 fps: late %d (%.1f%%, target < 2%%)%n",
                late, 100.0 * late / shows);
    }
}
