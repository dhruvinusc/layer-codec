package codec.encoder;

import codec.common.CmpFormat;
import codec.common.CmpFormat.Header;
import codec.common.ColorConv;
import codec.common.FrameEncoder;
import codec.common.Pad;
import codec.common.RunLength;
import codec.common.Types.Frame;
import codec.common.Types.Labels;
import codec.common.Types.VideoInfo;
import codec.common.VideoIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Entry point for {@code myencoder input_video.mp4 n1 n2} (brief, part 1).
 *
 * <p>Pipeline (wire-up W1, docs/p1_report.md): read upright frames, pad to 16, segment from motion
 * ({@link Segmenter.Run}, labels one frame behind), then for each labelled frame 8x8 DCT on R, G, B,
 * quantize with 2^n1 (foreground) or 2^n2 (background), and write the .cmp ({@link FrameEncoder},
 * {@link CmpFormat.Writer}, zlib on). Frames and labels are both FIFO, so they stay matched.
 *
 * <p>Optional, off by default: {@code --mode rle} writes mode 1 (zigzag + run-length, P4-B) instead
 * of the baseline layout. The spec command line {@code myencoder input_video.mp4 n1 n2} is unchanged.
 */
public final class EncoderMain {

    /**
     * Largest useful exponent. For an 8x8 DCT of level-shifted 8-bit samples the DC term
     * is at most 1024 = 2^10 in magnitude, so a step above 2^10 only zeroes every block.
     */
    static final int MAX_N = 10;
    static final int MIN_N = 0;

    static final String USAGE = String.join("\n",
            "usage: myencoder input_video.mp4 n1 n2",
            "  n1  quantization exponent for FOREGROUND blocks (step 2^n1), integer " + MIN_N + " to " + MAX_N,
            "  n2  quantization exponent for BACKGROUND blocks (step 2^n2), integer " + MIN_N + " to " + MAX_N,
            "  writes input_video.cmp next to the input",
            "optional (off by default):",
            "  --mode rle   zigzag + run-length coefficients (extra credit, P4-B)");

    /** A validated command line. {@code output} is derived from {@code input}, never typed by the user. */
    record Args(Path input, int n1, int n2, Path output, boolean rle) {}

    private EncoderMain() {
    }

    public static void main(String[] argv) {
        Args args;
        try {
            args = parse(argv);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        try {
            System.out.println(encode(args));
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        }
    }

    /** Encodes the whole clip; returns a one-line summary. */
    static String encode(Args a) throws Exception {
        long t0 = System.nanoTime();
        long segNanos = 0;
        long codeNanos = 0;
        int frames = 0;
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        ExecutorService pool = Executors.newFixedThreadPool(cores);
        try (VideoIO.Reader in = VideoIO.open(a.input())) {
            VideoInfo info = in.info();
            Header h = header(info, a);
            Segmenter.Run seg = new Segmenter.Run(info.paddedW(), info.paddedH(), Segmenter.SegConfig.DEFAULT,
                    GlobalMotion.GmConfig.DEFAULT, MotionEstimator.HierParams.DEFAULT);
            ArrayDeque<Frame> waiting = new ArrayDeque<>();     // padded frames waiting for their labels
            try (CmpFormat.Writer out = new CmpFormat.Writer(a.output(), h)) {
                Frame f = Frame.allocate(info.width(), info.height());
                while (in.read(f)) {
                    Frame p = Pad.toMacroblocks(f);
                    waiting.add(p);
                    long s0 = System.nanoTime();
                    List<Labels> ready = seg.push(ColorConv.luma(p));
                    segNanos += System.nanoTime() - s0;
                    codeNanos += write(out, h, waiting, ready, pool, cores);
                    frames += ready.size();
                }
                List<Labels> rest = seg.finish();
                codeNanos += write(out, h, waiting, rest, pool, cores);
                frames += rest.size();
                if (!waiting.isEmpty()) {                       // 1-frame clip: no motion, all background
                    frames += waiting.size();
                    codeNanos += write(out, h, waiting, List.of(new Labels(info.mbCols(), info.mbRows(),
                            new byte[info.mbCols() * info.mbRows()])), pool, cores);
                }
            }
        } finally {
            pool.shutdown();
        }
        double wall = (System.nanoTime() - t0) / 1e9;
        return String.format("encoded  : %s -> %s  frames %d  n1 %d  n2 %d  mode %s  size %.1f MB  "
                + "segmentation %.0f ms/frame  compression %.0f ms/frame  wall %.1f s",
                a.input().getFileName(), a.output().getFileName(), frames, a.n1(), a.n2(), a.rle() ? "rle" : "baseline",
                Files.size(a.output()) / 1e6, segNanos / 1e6 / Math.max(1, frames), codeNanos / 1e6 / Math.max(1, frames), wall);
    }

    /** Header from the clip facts; mode 1 (run-length) only when asked for. */
    static Header header(VideoInfo info, Args a) {
        Header h = Header.baseline(info, a.n1(), a.n2(), true);
        if (!a.rle()) {
            return h;
        }
        int flags = (h.flags() & ~CmpFormat.MODE_MASK) | (CmpFormat.MODE_RLE << CmpFormat.MODE_SHIFT);
        return new Header(flags, h.width(), h.height(), h.paddedW(), h.paddedH(), h.fpsNum(), h.fpsDen(), 0, h.n1(), h.n2(), 0);
    }

    /** Encodes one waiting frame per label set, in order; returns the nanoseconds spent. */
    private static long write(CmpFormat.Writer out, Header h, ArrayDeque<Frame> waiting, List<Labels> ready,
                              ExecutorService pool, int cores) throws Exception {
        long t0 = System.nanoTime();
        for (Labels l : ready) {
            byte[] payload = FrameEncoder.encodeParallel(baselineOf(h), waiting.poll(), l, pool, 2 * cores);
            if (h.mode() == CmpFormat.MODE_RLE) {
                // P4-B: same coefficients, re-laid out as zigzag + run-length (as CmpRecode does).
                byte[] types = new byte[h.blockCount()];
                short[] coef = new short[h.blockCount() * CmpFormat.COEF_PER_BLOCK];
                CmpFormat.decodeBaseline(baselineOf(h), payload, types, coef);
                payload = RunLength.encode(h, types, coef);
            }
            out.writePayload(payload);
        }
        return System.nanoTime() - t0;
    }

    /** The same header with mode 0, for the baseline transform. */
    private static Header baselineOf(Header h) {
        if (h.mode() == CmpFormat.MODE_BASELINE) {
            return h;
        }
        return new Header(h.flags() & ~CmpFormat.MODE_MASK, h.width(), h.height(), h.paddedW(), h.paddedH(),
                h.fpsNum(), h.fpsDen(), 0, h.n1(), h.n2(), 0);
    }

    /** Parses and validates; throws IllegalArgumentException with a message meant for the user. */
    static Args parse(String[] argv) {
        boolean rle = false;
        if (argv.length == 5 && argv[3].equals("--mode")) {
            rle = switch (argv[4]) {
                case "rle" -> true;
                case "baseline" -> false;
                default -> throw new IllegalArgumentException("--mode must be rle or baseline, got '" + argv[4] + "'");
            };
        } else if (argv.length != 3) {
            throw new IllegalArgumentException("expected 3 arguments, got " + argv.length);
        }
        Path input = Path.of(argv[0]);
        if (!Files.isRegularFile(input)) {
            throw new IllegalArgumentException("input file not found: " + input);
        }
        int n1 = parseExponent("n1", argv[1]);
        int n2 = parseExponent("n2", argv[2]);
        return new Args(input, n1, n2, cmpPathFor(input), rle);
    }

    static int parseExponent(String name, String text) {
        int n;
        try {
            n = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer, got '" + text + "'");
        }
        if (n < MIN_N || n > MAX_N) {
            throw new IllegalArgumentException(name + " must be in " + MIN_N + ".." + MAX_N + ", got " + n);
        }
        return n;
    }

    /** {@code dir/input_video.mp4} becomes {@code dir/input_video.cmp} (brief: same base name). */
    static Path cmpPathFor(Path input) {
        String file = input.getFileName().toString();
        int dot = file.lastIndexOf('.');
        String stem = dot > 0 ? file.substring(0, dot) : file;
        return input.resolveSibling(stem + ".cmp");
    }
}
