package codec.decoder;

import codec.common.CmpFormat;
import codec.common.Display;
import codec.common.Types;
import codec.common.VideoIO;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * {@code --workers N} overrides the decode worker count (4 vs 6 comparison, P3-L5), and hidden
 * {@code --trace file.csv} writes per-frame timing for diagnosis.
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
    record Args(Path cmp, Path original, int workers, Path trace) {}

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
            OriginalVideo original = a.original() == null ? null : new OriginalVideo(a.original(), h.width(), h.height());
            if (original != null) {
                System.out.printf("original : %s (side by side: press s; timing runs are made without it)%n",
                        a.original().getFileName());
            }
            System.out.println("keys     : space play/pause, right step, left step back, q quit, "
                    + "s side by side, z 1:1 zoom (drag to pan), d foreground overlay");
            try (DecodePipeline p = new DecodePipeline(r, workers, cap); OriginalVideo o = original) {
                FrameCache cache = new FrameCache(p);
                // With an original, the window is two frames wide so side by side fits; single
                // view is then centered in it.
                int windowW = o == null ? h.width() : 2 * h.width();
                Display d = new Display("mydecoder  " + name, windowW, h.height());
                try {
                    Player player = new Player(h, r.frameCount(), source(cache), screen(d, h.blocksX(), o),
                            System::nanoTime, System.out, name + " (" + workers + " workers)");
                    player.run();
                    if (a.trace() != null) {
                        try (PrintStream csv = new PrintStream(a.trace().toFile(), StandardCharsets.UTF_8)) {
                            player.writeTrace(csv);
                        }
                        System.out.println("trace    : " + a.trace());
                    }
                } finally {
                    d.close();
                }
            }
        }
    }

    /** Adapts the step-back cache to the player's source interface (get + pre-roll readiness). */
    private static Player.Source source(FrameCache cache) {
        return new Player.Source() {
            @Override
            public FrameCache.Entry get(int k) throws IOException, InterruptedException {
                return cache.get(k);
            }

            @Override
            public boolean isReady(int k) {
                return cache.isReady(k);
            }
        };
    }

    /**
     * Adapts the Swing display to the player's screen interface: the decoded frame, its block
     * types (overlay) and, if given, the original frame with the same index (side by side).
     */
    private static Player.Screen screen(Display d, int blocksX, OriginalVideo original) {
        return new Player.Screen() {
            private boolean originalFailed;

            @Override
            public void show(FrameCache.Entry e, String status) {
                int[] right = null;
                if (original != null && !originalFailed) {
                    try {
                        right = original.get(e.index());
                    } catch (IOException ex) {
                        originalFailed = true; // keep playing the decoded video; say why once
                        System.err.println("warning  : original stopped: " + ex.getMessage());
                    }
                }
                d.setStatus(status);
                d.showLayers(e.rgb(), e.types(), blocksX, right, e.width(), e.height());
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
        Path trace = null;
        for (int i = 1; i < argv.length; i++) {
            if (argv[i].equals("--trace") && i + 1 < argv.length) {
                trace = Path.of(argv[++i]);
            } else if (argv[i].equals("--workers") && i + 1 < argv.length) {
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
        return new Args(cmp, original, workers, trace);
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

    /**
     * The original mp4's frames for side by side (P3.4), read in step with the decoded frames.
     *
     * <p>The mp4 can only be read forward (an FFmpeg pipe, through {@link VideoIO}), so frames are
     * read as the player moves ahead, and the last {@link #RING} are kept packed (0xRRGGBB) for
     * stepping back. A step back past the ring reopens the clip and reads forward again from
     * frame 0: slow for frames near the end of SAL (a few seconds), but rare. Frames come out
     * upright and at true size, exactly like the decoder's output, so the two halves line up.
     * Not thread-safe: only the player thread uses it.
     */
    static final class OriginalVideo implements AutoCloseable {
        /** Same depth as the decoded frames' step-back cache (P3.2). */
        static final int RING = FrameCache.DEFAULT_CAPACITY;

        private final Path mp4;
        private final Types.Frame scratch;
        private final int[][] slots = new int[RING][];
        private final Map<Integer, Integer> slotOf = new HashMap<>();
        private VideoIO.Reader reader;
        private int next;
        private boolean ended;
        private long inserted;
        private int reopens;

        OriginalVideo(Path mp4, int width, int height) throws IOException {
            Types.VideoInfo info = VideoIO.probe(mp4);
            if (info.width() != width || info.height() != height) {
                throw new IOException("original " + mp4.getFileName() + " is " + info.width() + "x"
                        + info.height() + " upright, but the .cmp is " + width + "x" + height
                        + ": not the same video");
            }
            this.mp4 = mp4;
            this.scratch = Types.Frame.allocate(width, height);
            this.reader = VideoIO.open(mp4);
        }

        /** Frame k, packed, or null if the mp4 ends before frame k. */
        int[] get(int k) throws IOException {
            Integer slot = slotOf.get(k);
            if (slot != null) {
                return slots[slot];
            }
            if (k < next) {
                reopen();
            }
            while (next <= k && !ended) {
                if (!reader.read(scratch)) {
                    ended = true;
                    break;
                }
                int idx = next++;
                if (idx > k - RING) {
                    insert(idx);
                }
            }
            slot = slotOf.get(k);
            return slot == null ? null : slots[slot];
        }

        private void reopen() throws IOException {
            reader.close();
            reader = VideoIO.open(mp4);
            next = 0;
            ended = false;
            reopens++;
        }

        private void insert(int idx) {
            int slot = (int) (inserted++ % RING);
            if (slots[slot] == null) {
                slots[slot] = new int[scratch.width() * scratch.height()];
            } else {
                slotOf.values().remove(slot);
            }
            int[] dst = slots[slot];
            int[] r = scratch.r();
            int[] g = scratch.g();
            int[] b = scratch.b();
            for (int i = 0; i < dst.length; i++) {
                dst[i] = (r[i] << 16) | (g[i] << 8) | b[i];
            }
            slotOf.put(idx, slot);
        }

        /** How often a step back past the ring forced a reread from frame 0 (test hook). */
        int reopens() {
            return reopens;
        }

        @Override
        public void close() {
            reader.close();
        }
    }
}
