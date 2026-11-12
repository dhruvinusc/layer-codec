package codec.decoder;

import codec.common.CmpFormat;
import codec.common.Display;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point for {@code mydecoder input_video.cmp} (brief, part 3).
 *
 * <p>The optional second argument (the original mp4) enables side-by-side comparison
 * (03 D8). It is off by default so the spec command line is unchanged.
 *
 * <p>Hidden timing mode (P3, off by default so the spec command line is unchanged):
 * {@code mydecoder input.cmp --bench [--workers 1,2,6]} decodes every frame headless, first
 * serially on one core, then through {@link DecodePipeline}, and prints ms/frame and fps.
 *
 * <p>A plain run plays the file (P3.3): decode-ahead pipeline, 30-frame step-back cache, steady
 * clock with frame dropping, status line on screen, timing report on stdout. Hidden option
 * {@code --workers N} overrides the decode worker count (4 vs 6 comparison, P3-L5).
 */
public final class DecoderMain {

    static final String USAGE = String.join("\n",
            "usage: mydecoder input_video.cmp [original_video.mp4]",
            "  plays the decoded video at its original frame rate",
            "  optional original_video.mp4 enables side-by-side comparison",
            "  keys: space play/pause, right step, left step back, q quit");

    /**
     * A validated command line. {@code original} is null when side-by-side is not requested;
     * {@code workers} is 0 for the default.
     */
    record Args(Path cmp, Path original, int workers) {}

    private DecoderMain() {
    }

    /** Frames timed in the serial single-core pass (after one warm-up pass over the same frames). */
    static final int SERIAL_FRAMES = 30;

    /** Parsed hidden bench command line. */
    record Bench(Path cmp, List<Integer> workers) {}

    public static void main(String[] argv) {
        if (argv.length >= 2 && argv[1].equals("--bench")) {
            try {
                runBench(parseBench(argv));
            } catch (IllegalArgumentException e) {
                System.err.println("error: " + e.getMessage());
                System.exit(2);
            } catch (IOException e) {
                System.err.println("error: " + e.getMessage());
                System.exit(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.exit(1);
            }
            return;
        }
        Args args;
        try {
            args = parse(argv);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        int code = 0;
        try {
            play(args);
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            code = 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            code = 1;
        } catch (IllegalStateException e) {
            System.err.println("error: " + e.getMessage());
            code = 1;
        }
        // Pipeline threads are joined and the window disposed by now; exit so no AWT thread lingers.
        System.exit(code);
    }

    /** Opens the file, plays it, and tears everything down in reverse order. */
    static void play(Args a) throws IOException, InterruptedException {
        try (CmpFormat.Reader r = new CmpFormat.Reader(a.cmp())) {
            CmpFormat.Header h = r.header();
            int workers = a.workers() > 0 ? a.workers() : DecodePipeline.defaultWorkers();
            int cap = DecodePipeline.defaultCapacity(h, workers);
            String name = a.cmp().getFileName().toString();
            System.out.printf("file     : %s  %dx%d  n1=%d n2=%d  frames=%d  fps=%d/%d%n", name, h.width(),
                    h.height(), h.n1(), h.n2(), r.frameCount(), h.fpsNum(), h.fpsDen());
            System.out.printf("decode   : %d workers, window %d frames, step-back cache %d frames (%.0f MB)%n",
                    workers, cap, FrameCache.DEFAULT_CAPACITY,
                    FrameCache.bytesFor(h, FrameCache.DEFAULT_CAPACITY) / 1e6);
            if (a.original() != null) {
                System.out.println("note     : side by side with the original arrives in P3.4; ignoring " + a.original());
            }
            System.out.println("keys     : space play/pause, right step, left step back, q quit");
            try (DecodePipeline p = new DecodePipeline(r, workers, cap)) {
                FrameCache cache = new FrameCache(p);
                Display d = new Display("mydecoder  " + name, h.width(), h.height());
                try {
                    new Player(h, r.frameCount(), cache::get, screen(d), System::nanoTime, System.out,
                            name + " (" + workers + " workers)").run();
                } finally {
                    d.close();
                }
            }
        }
    }

    /** Adapts the Swing display to the player's screen interface. */
    private static Player.Screen screen(Display d) {
        return new Player.Screen() {
            @Override
            public void show(FrameCache.Entry e, String status) {
                d.setStatus(status);
                d.show(e.rgb(), e.width(), e.height());
            }

            @Override
            public Display.Key waitKey(long nanos) {
                return nanos <= 0 ? d.pollKey() : d.waitKey(nanos);
            }
        };
    }

    static Args parse(String[] argv) {
        if (argv.length < 1) {
            throw new IllegalArgumentException("expected 1 or 2 arguments, got 0");
        }
        Path cmp = requireFile(argv[0]);
        Path original = null;
        int workers = 0;
        for (int i = 1; i < argv.length; i++) {
            if (argv[i].equals("--workers") && i + 1 < argv.length) {
                workers = Integer.parseInt(argv[++i]);
                if (workers < 1) {
                    throw new IllegalArgumentException("workers must be >= 1, got " + workers);
                }
            } else if (original == null && !argv[i].startsWith("--")) {
                original = requireFile(argv[i]);
            } else {
                throw new IllegalArgumentException("unexpected argument " + argv[i]);
            }
        }
        return new Args(cmp, original, workers);
    }

    static Bench parseBench(String[] argv) {
        Path cmp = requireFile(argv[0]);
        List<Integer> workers = new ArrayList<>();
        if (argv.length == 4 && argv[2].equals("--workers")) {
            for (String w : argv[3].split(",")) {
                int n = Integer.parseInt(w.trim());
                if (n < 1) {
                    throw new IllegalArgumentException("workers must be >= 1, got " + n);
                }
                workers.add(n);
            }
        } else if (argv.length == 2) {
            workers.add(DecodePipeline.defaultWorkers());
        } else {
            throw new IllegalArgumentException("usage: mydecoder input.cmp --bench [--workers 1,2,6]");
        }
        return new Bench(cmp, workers);
    }

    /** Serial one-core decode time, then pipeline throughput for each worker count. */
    static void runBench(Bench b) throws IOException, InterruptedException {
        try (CmpFormat.Reader r = new CmpFormat.Reader(b.cmp())) {
            CmpFormat.Header h = r.header();
            int n = r.frameCount();
            System.out.printf("bench    : %s  %dx%d  n1=%d n2=%d  frames=%d  fps=%d/%d  cores=%d%n",
                    b.cmp().getFileName(), h.width(), h.height(), h.n1(), h.n2(), n,
                    h.fpsNum(), h.fpsDen(), Runtime.getRuntime().availableProcessors());
            double budget = 1000.0 * h.fpsDen() / h.fpsNum();
            int m = Math.min(SERIAL_FRAMES, n);
            DecodeCore.Scratch s = new DecodeCore.Scratch(h);
            serialPass(r, s, m); // JIT warm-up
            long t0 = System.nanoTime();
            serialPass(r, s, m);
            double serialMs = (System.nanoTime() - t0) / 1e6 / m;
            System.out.printf("serial   : 1 core, %d frames, %.1f ms/frame (budget %.1f ms)%n", m, serialMs, budget);
            for (int w : b.workers()) {
                pipelinePass(r, w, n, budget);
            }
        }
    }

    private static void serialPass(CmpFormat.Reader r, DecodeCore.Scratch s, int m) throws IOException {
        for (int k = 0; k < m; k++) {
            DecodeCore.decode(r.header(), r.payload(k), s);
        }
    }

    private static void pipelinePass(CmpFormat.Reader r, int w, int n, double budget)
            throws IOException, InterruptedException {
        int cap = DecodePipeline.defaultCapacity(r.header(), w);
        try (DecodePipeline p = new DecodePipeline(r, w, cap)) {
            long t0 = System.nanoTime();
            for (int k = 0; k < n; k++) {
                p.get(k);
            }
            double ms = (System.nanoTime() - t0) / 1e6 / n;
            System.out.printf("pipeline : %d workers, capacity %d, %.1f ms/frame, %.1f fps, %.2fx real time, peak buffered %d%n",
                    w, cap, ms, 1000.0 / ms, budget / ms, p.peakBuffered());
        }
    }

    private static Path requireFile(String text) {
        Path p = Path.of(text);
        if (!Files.isRegularFile(p)) {
            throw new IllegalArgumentException("file not found: " + p);
        }
        return p;
    }
}
