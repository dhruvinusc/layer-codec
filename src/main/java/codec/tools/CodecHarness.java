package codec.tools;

import codec.common.CmpFormat;
import codec.common.CmpFormat.Header;
import codec.common.FrameEncoder;
import codec.common.Types;
import codec.common.VideoIO;
import codec.decoder.DecodeCore;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;

/**
 * P2 end-to-end check before segmentation exists:
 * {@code CodecHarness <clip> n1 n2 [--frames N] [--labels checker|all-fg|all-bg] [--raw] [--threads T]}.
 *
 * <p>Reads frames, builds FAKE labels, encodes with {@link FrameEncoder} into
 * {@code out/<clip>_p2.cmp}, reads the file back with {@link CmpFormat.Reader} and
 * {@link DecodeCore}, and reports sizes, ms per frame, PSNR (all / fg / bg) and a SHA-256 of the
 * file (run twice: same hash means deterministic). Saves original | decoded PNGs (half scale and a
 * 1:1 crop) of the middle frame to {@code out/evidence/}.
 */
public final class CodecHarness {

    static final String USAGE =
            "usage: CodecHarness <clip> n1 n2 [--frames N] [--labels checker|all-fg|all-bg] [--raw] [--threads T]";
    private static final int CROP = 256;

    private CodecHarness() {
    }

    record Args(Path clip, int n1, int n2, int frames, String labels, boolean raw, int threads) {
    }

    static Args parse(String[] a) {
        if (a.length < 3) {
            throw new IllegalArgumentException(USAGE);
        }
        int frames = Integer.MAX_VALUE;
        String labels = "checker";
        boolean raw = false;
        int threads = Runtime.getRuntime().availableProcessors();
        for (int i = 3; i < a.length; i++) {
            switch (a[i]) {
                case "--frames" -> frames = Integer.parseInt(a[++i]);
                case "--labels" -> labels = a[++i];
                case "--raw" -> raw = true;
                case "--threads" -> threads = Integer.parseInt(a[++i]);
                default -> throw new IllegalArgumentException("unknown option " + a[i] + "\n" + USAGE);
            }
        }
        if (!labels.equals("checker") && !labels.equals("all-fg") && !labels.equals("all-bg")) {
            throw new IllegalArgumentException("labels must be checker, all-fg or all-bg");
        }
        return new Args(Path.of(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]), frames, labels, raw, threads);
    }

    /** Fake macroblock labels: checkerboard (1 where col + row is even), all 1, or all 0. */
    static Types.Labels fakeLabels(String mode, int cols, int rows) {
        byte[] id = new byte[cols * rows];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                boolean fg = switch (mode) {
                    case "all-fg" -> true;
                    case "all-bg" -> false;
                    default -> (c + r) % 2 == 0;
                };
                id[r * cols + c] = (byte) (fg ? 1 : 0);
            }
        }
        return new Types.Labels(cols, rows, id);
    }

    public static void main(String[] argv) throws Exception {
        Args a;
        try {
            a = parse(argv);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        System.out.println(run(a, Path.of("out")));
    }

    /** Runs the harness and returns the report. Public pieces kept small for tests. */
    static String run(Args a, Path outDir) throws Exception {
        Files.createDirectories(outDir.resolve("evidence"));
        String stem = a.clip().getFileName().toString().replaceFirst("\\.[^.]+$", "");
        Path cmp = outDir.resolve(stem + "_p2.cmp");
        ExecutorService pool = Executors.newFixedThreadPool(a.threads());
        try {
            long[] enc = encode(a, cmp, pool);
            return decodeAndReport(a, cmp, stem, outDir, pool, enc);
        } finally {
            pool.shutdown();
        }
    }

    /** Returns {frames, encode nanos}. */
    private static long[] encode(Args a, Path cmp, ExecutorService pool) throws Exception {
        long nanos = 0;
        int n = 0;
        try (VideoIO.Reader video = VideoIO.open(a.clip())) {
            Types.VideoInfo info = video.info();
            Header h = Header.baseline(info, a.n1(), a.n2(), !a.raw());
            Types.Labels labels = fakeLabels(a.labels(), info.mbCols(), info.mbRows());
            try (CmpFormat.Writer w = new CmpFormat.Writer(cmp, h)) {
                Types.Frame f;
                while (n < a.frames() && (f = video.read()) != null) {
                    long t0 = System.nanoTime();
                    w.writePayload(FrameEncoder.encodeParallel(h, f, labels, pool, a.threads() * 2));
                    nanos += System.nanoTime() - t0;
                    n++;
                }
            }
        }
        return new long[] {n, nanos};
    }

    private static String decodeAndReport(Args a, Path cmp, String stem, Path outDir,
                                          ExecutorService pool, long[] enc) throws Exception {
        Psnr psnr = new Psnr();
        long nanos = 0;
        long stored = Files.size(cmp);
        Header h;
        try (CmpFormat.Reader r = new CmpFormat.Reader(cmp);
             VideoIO.Reader video = VideoIO.open(a.clip())) {
            h = r.header();
            int mid = r.frameCount() / 2;
            for (int k = 0; k < r.frameCount(); k++) {
                Types.Frame orig = video.read();
                long t0 = System.nanoTime();
                DecodeCore.Decoded d = DecodeCore.decodeParallel(h, r.payload(k), pool, a.threads() * 2);
                nanos += System.nanoTime() - t0;
                psnr.add(orig, d.frame(), d.types(), h.blocksX());
                if (k == mid) {
                    saveImages(orig, d.frame(), outDir.resolve("evidence"),
                            String.format("p2_%s_n%d_%d_%s", stem, a.n1(), a.n2(), a.labels()));
                }
            }
        }
        int frames = (int) enc[0];
        double rawMb = h.baselineRawBytes() / 1e6;
        double storedMb = (stored - CmpFormat.HEADER_BYTES - 8.0 * frames) / frames / 1e6 - 8e-6;
        return String.join("\n",
                String.format("clip %s  n1 %d  n2 %d  labels %s  zlib %s  threads %d  frames %d  size %dx%d (padded %dx%d)",
                        stem, a.n1(), a.n2(), a.labels(), !a.raw(), a.threads(), frames,
                        h.width(), h.height(), h.paddedW(), h.paddedH()),
                String.format("bytes/frame raw %.2f MB  stored %.2f MB  ratio %.2fx  file %.1f MB",
                        rawMb, storedMb, rawMb / storedMb, stored / 1e6),
                String.format("encode %.1f ms/frame (DCT+quant+serialize+zlib)  decode %.1f ms/frame (read+inflate+dequant+IDCT)",
                        enc[1] / 1e6 / frames, nanos / 1e6 / frames),
                psnr.summary(),
                "sha256 " + sha256(cmp),
                "file " + cmp);
    }

    private static void saveImages(Types.Frame o, Types.Frame d, Path dir, String name) throws IOException {
        int w = o.width();
        int hh = o.height();
        BufferedImage full = new BufferedImage(w, (hh + 1) / 2, BufferedImage.TYPE_INT_RGB);
        drawHalf(full, o, 0);
        drawHalf(full, d, (w + 1) / 2);
        ImageIO.write(full, "png", dir.resolve(name + "_half.png").toFile());
        int cw = Math.min(CROP, w);
        int ch = Math.min(CROP, hh);
        int x0 = (w - cw) / 2;
        int y0 = (hh - ch) / 2;
        BufferedImage crop = new BufferedImage(cw * 2 + 4, ch, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) {
                int i = (y0 + y) * w + x0 + x;
                crop.setRGB(x, y, rgb(o, i));
                crop.setRGB(cw + 4 + x, y, rgb(d, i));
            }
        }
        ImageIO.write(crop, "png", dir.resolve(name + "_crop1to1.png").toFile());
    }

    /** 2x2 box downscale into the image at column offset {@code xOff}. */
    private static void drawHalf(BufferedImage img, Types.Frame f, int xOff) {
        int w = f.width();
        for (int y = 0; y + 1 < f.height(); y += 2) {
            for (int x = 0; x + 1 < w; x += 2) {
                int i = y * w + x;
                int r = (f.r()[i] + f.r()[i + 1] + f.r()[i + w] + f.r()[i + w + 1]) / 4;
                int g = (f.g()[i] + f.g()[i + 1] + f.g()[i + w] + f.g()[i + w + 1]) / 4;
                int b = (f.b()[i] + f.b()[i + 1] + f.b()[i + w] + f.b()[i + w + 1]) / 4;
                img.setRGB(xOff + x / 2, y / 2, (r << 16) | (g << 8) | b);
            }
        }
    }

    private static int rgb(Types.Frame f, int i) {
        return (f.r()[i] << 16) | (f.g()[i] << 8) | f.b()[i];
    }

    static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[1 << 20];
            int r;
            while ((r = in.read(buf)) > 0) {
                md.update(buf, 0, r);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
