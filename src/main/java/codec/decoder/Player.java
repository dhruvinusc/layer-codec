package codec.decoder;

import codec.common.CmpFormat.Header;
import codec.common.Display;
import java.io.IOException;
import java.io.PrintStream;
import java.util.function.LongSupplier;

/**
 * Playback state machine (H3 P3.3, 03 D8): steady clock, play, pause, step, step back, drop late
 * frames, counters.
 *
 * <p>Timing: frame i is due at t0 + (i - i0) * period, from the frame index (never "sleep one
 * period after the last frame", which drifts). This mirrors {@code codec.common.Clock}; it is
 * repeated here only because Clock's fake-time hook is package-private and Clock is frozen,
 * and the drop rule must be tested with a fake clock.
 *
 * <p>Drop rule: when frame {@code next} is due, the player shows the NEWEST frame that is
 * already due (target = i0 + floor((now - t0) / period)). Frames between next and target are
 * skipped and counted as dropped, so playback keeps the original rate instead of slowing down.
 * The target itself is always shown, even if decoding it made it late, so the picture always
 * moves forward. A frame shown more than half a period after its deadline counts as late.
 *
 * <p>Threads: runs on the caller's thread (the main thread), never on the Swing event thread;
 * the screen paints on the event thread. Decoding happens in the pipeline's workers.
 */
public final class Player {

    /** Where frames come from: in production {@link FrameCache}. */
    @FunctionalInterface
    interface Source {
        FrameCache.Entry get(int k) throws IOException, InterruptedException;

        /** True if frame k can be returned without waiting (used only for pre-roll). */
        default boolean isReady(int k) {
            return true;
        }
    }

    /** Where frames go: in production a {@link Display}. */
    interface Screen {
        void show(FrameCache.Entry e, String status);

        /** Waits up to nanos for a key; null on timeout. */
        Display.Key waitKey(long nanos);
    }

    /** Longest wait between key checks while playing, so keys feel instant. */
    static final long KEY_POLL_NANOS = 5_000_000L;
    /** Wait between key checks while paused. */
    static final long PAUSED_POLL_NANOS = 50_000_000L;
    /** Frames decoded ahead before the clock starts (pre-roll), like any video player's buffer. */
    static final int PREROLL_FRAMES = 6;
    /**
     * Repaints of frame 0 during pre-roll (P3.4b). Same picture, so invisible, but they run the
     * scaling and painting code enough times for Java to compile it (JIT) before the clock
     * starts. Mac run 4: late frames clustered in frames 7 to 62, the first 2 s, while that code
     * was still being compiled. About 10 ms each at 1080p, so 15 cost about 150 ms of start-up.
     */
    static final int WARM_PAINTS = 15;
    /** Longest pre-roll wait, so a very slow machine still starts. */
    static final long PREROLL_MAX_NANOS = 1_000_000_000L;
    /** Frames in each of the first and last windows of the thermal report (H3 P3.7). */
    static final int WINDOW = 100;

    private final Header header;
    private final int frameCount;
    private final Source source;
    private final Screen screen;
    private final LongSupplier now;
    private final PrintStream out;
    private final String label;
    private final long periodNum;
    private final long periodDen;

    private final long[] shownAt;
    private final boolean[] late;
    private final boolean[] dropped;
    private final long[] waitedNanos;
    private int shown = -1;
    private boolean playing = true;
    private boolean interrupted;
    private boolean reported;
    private long t0;
    private int i0;
    private int droppedCount;
    private int lateCount;
    private int paints;
    private long waitNanos;

    Player(Header header, int frameCount, Source source, Screen screen, LongSupplier now,
           PrintStream out, String label) {
        if (frameCount < 1) {
            throw new IllegalArgumentException("no frames to play");
        }
        this.header = header;
        this.frameCount = frameCount;
        this.source = source;
        this.screen = screen;
        this.now = now;
        this.out = out;
        this.label = label;
        this.periodNum = 1_000_000_000L * header.fpsDen();
        this.periodDen = header.fpsNum();
        this.shownAt = new long[frameCount];
        this.late = new boolean[frameCount];
        this.dropped = new boolean[frameCount];
        this.waitedNanos = new long[frameCount];
    }

    long periodNanos() {
        return periodNum / periodDen;
    }

    private long due(int i) {
        return t0 + (i - i0) * periodNum / periodDen;
    }

    private void anchor(int i) {
        t0 = now.getAsLong();
        i0 = i;
    }

    /** Plays until the viewer quits. Prints the timing report once, at the end of the first pass. */
    public Report run() throws IOException, InterruptedException {
        if (!preroll()) {
            Report r = report();
            out.println(r.format());
            return r;
        }
        while (true) {
            Display.Key k;
            if (playing) {
                k = playStep();
            } else {
                k = screen.waitKey(PAUSED_POLL_NANOS);
            }
            if (k == Display.Key.QUIT) {
                Report r = report();
                if (!reported) {
                    out.println(r.format());
                }
                return r;
            }
            if (k != null) {
                handle(k);
            }
        }
    }

    /**
     * Shows frame 0, repaints it {@link #WARM_PAINTS} times to warm the paint path (P3.4b), waits
     * until the next few frames are decoded (or 1 s passes in all), then starts the
     * clock with frame 0 counted as shown at that moment. Without this, window creation, the first
     * cold decode and the first paint (about half a second on the Mac, P3.3 run 1) were counted as
     * falling behind, so the first ~19 frames were dropped and every clip "played" 9 to 17% short.
     * Returns false if the viewer quit during pre-roll.
     */
    private boolean preroll() throws IOException, InterruptedException {
        FrameCache.Entry first = source.get(0);
        show(0, first);
        int upTo = Math.min(frameCount - 1, PREROLL_FRAMES);
        long start = now.getAsLong();
        int warm = 0;
        while ((warm < WARM_PAINTS || !readyThrough(upTo)) && now.getAsLong() - start < PREROLL_MAX_NANOS) {
            Display.Key k;
            if (warm < WARM_PAINTS) {
                screen.show(first, status()); // warm-up repaint, not counted as a paint
                warm++;
                k = screen.waitKey(0);
            } else {
                k = screen.waitKey(KEY_POLL_NANOS);
            }
            if (k == Display.Key.QUIT) {
                return false;
            }
        }
        anchor(0);
        shownAt[0] = t0; // frame 0 has been on screen since before t0; its slot ends at t0 + period
        return true;
    }

    private boolean readyThrough(int upTo) {
        for (int k = 1; k <= upTo; k++) {
            if (!source.isReady(k)) {
                return false;
            }
        }
        return true;
    }

    /** One step of playing: wait for the next deadline (watching keys) or show the newest due frame. */
    private Display.Key playStep() throws IOException, InterruptedException {
        int next = shown + 1;
        if (next >= frameCount) {
            playing = false;
            finishFirstPass();
            refresh();
            return null;
        }
        long wait = due(next) - now.getAsLong();
        if (wait > 0) {
            return screen.waitKey(Math.min(wait, KEY_POLL_NANOS));
        }
        long behind = (now.getAsLong() - t0) * periodDen / periodNum;
        int target = (int) Math.min(frameCount - 1, Math.max(next, i0 + behind));
        for (int d = next; d < target; d++) {
            dropped[d] = true;
            droppedCount++;
        }
        long w0 = now.getAsLong();
        FrameCache.Entry e = source.get(target);
        long waited = now.getAsLong() - w0;
        waitNanos += waited;
        waitedNanos[target] = waited;
        long shownTime = show(target, e);
        long lateBy = shownTime - due(target);
        if (lateBy > periodNanos() / 2) {
            late[target] = true;
            lateCount++;
        }
        return screen.waitKey(0);
    }

    private void handle(Display.Key k) throws IOException, InterruptedException {
        switch (k) {
            case TOGGLE_PLAY -> {
                if (playing) {
                    pause();
                } else {
                    playing = true;
                    interrupted = true;
                    if (shown >= frameCount - 1) {
                        shown = -1; // at the end: play again from the start
                    }
                    anchor(shown + 1);
                }
            }
            case STEP_FORWARD -> {
                pause();
                if (shown + 1 < frameCount) {
                    show(shown + 1, source.get(shown + 1));
                }
            }
            case STEP_BACK -> {
                pause();
                if (shown > 0) {
                    show(shown - 1, source.get(shown - 1));
                }
            }
            default -> {
            }
        }
    }

    private void pause() throws IOException, InterruptedException {
        if (playing) {
            playing = false;
            interrupted = true;
            refresh();
        }
    }

    private void refresh() throws IOException, InterruptedException {
        if (shown >= 0) {
            screen.show(source.get(shown), status());
        }
    }

    /** Paints frame index and returns the time the paint finished. */
    private long show(int index, FrameCache.Entry e) {
        shown = index;
        screen.show(e, status());
        paints++;
        long t = now.getAsLong();
        if (shownAt[index] == 0 && !interrupted) {
            shownAt[index] = t;
        }
        return t;
    }

    /**
     * Per-frame timing as CSV (hidden {@code --trace}, P3.3 diagnosis): one row per frame of the
     * first pass with its deadline and paint time relative to the clock start, how long the player
     * waited for the decoder, and whether it was dropped or late. Shows whether lost time comes in
     * a few long stalls (disk, memory clean-up) or many small ones (CPU).
     */
    void writeTrace(PrintStream csv) {
        csv.println("frame,due_ms,shown_ms,wait_ms,dropped,late");
        for (int i = 0; i < frameCount; i++) {
            long d = due(i) - t0;
            String shownMs = shownAt[i] > 0 ? String.format("%.2f", (shownAt[i] - t0) / 1e6) : "";
            csv.printf("%d,%.2f,%s,%.2f,%d,%d%n", i, d / 1e6, shownMs, waitedNanos[i] / 1e6,
                    dropped[i] ? 1 : 0, late[i] ? 1 : 0);
        }
    }

    String status() {
        double secs = (double) Math.max(shown, 0) * header.fpsDen() / header.fpsNum();
        return String.format("frame %d/%d  %.2f s  n1=%d n2=%d  dropped %d  late %d  %s",
                shown + 1, frameCount, secs, header.n1(), header.n2(), droppedCount, lateCount,
                playing ? "PLAYING" : "PAUSED");
    }

    private void finishFirstPass() {
        if (!reported) {
            reported = true;
            out.println(report().format());
        }
    }

    /** Timing summary. Honest only when the first pass ran without pause or step. */
    Report report() {
        long first = shownAt[0];
        long last = shownAt[frameCount - 1];
        double measured = first > 0 && last > 0 ? (last - first + periodNanos()) / 1e9 : Double.NaN;
        double expected = (double) frameCount * header.fpsDen() / header.fpsNum();
        return new Report(label, frameCount, droppedCount, lateCount, interrupted, measured, expected,
                paints == 0 ? 0 : waitNanos / 1e6 / paints, window(0), window(Math.max(0, frameCount - WINDOW)));
    }

    private Window window(int from) {
        int to = Math.min(frameCount, from + WINDOW);
        int shownN = 0;
        int drops = 0;
        int lates = 0;
        long a = 0;
        long b = 0;
        for (int i = from; i < to; i++) {
            drops += dropped[i] ? 1 : 0;
            lates += late[i] ? 1 : 0;
            if (shownAt[i] > 0) {
                shownN++;
                a = a == 0 ? shownAt[i] : a;
                b = shownAt[i];
            }
        }
        double span = shownN > 0 ? (b - a + periodNanos()) / 1e9 : Double.NaN;
        return new Window(from, to - 1, shownN, drops, lates, shownN / span);
    }

    /** Frames from..to (inclusive): how many were shown, dropped, late, and shown frames per second. */
    public record Window(int from, int to, int shown, int dropped, int late, double fps) {
    }

    /** The numbers that go into RESULTS P3.3 / P3.7. */
    public record Report(String label, int frames, int dropped, int late, boolean interrupted,
                         double measuredSeconds, double expectedSeconds, double meanWaitMs,
                         Window first, Window last) {

        public String format() {
            StringBuilder s = new StringBuilder();
            s.append(String.format("play     : %s  frames %d  dropped %d (%.1f%%)  late %d (%.1f%%)%n",
                    label, frames, dropped, 100.0 * dropped / frames, late, 100.0 * late / frames));
            if (interrupted || Double.isNaN(measuredSeconds)) {
                s.append("duration : not measured (paused, stepped or quit before the end)\n");
            } else {
                s.append(String.format("duration : %.2f s played, clip %.2f s, error %+.2f%%%n", measuredSeconds,
                        expectedSeconds, 100.0 * (measuredSeconds - expectedSeconds) / expectedSeconds));
            }
            s.append(String.format("decode   : mean wait for a frame %.1f ms%n", meanWaitMs));
            if (interrupted) {
                // Paint times stop being recorded at the first pause or step, so per-window
                // shown counts and fps would be wrong (Mac Nov 12: "shown 0 ... NaN fps").
                s.append("windows  : first/last 100 frames not measured (paused or stepped)\n");
            } else {
                s.append(line("first", first)).append(line("last ", last));
            }
            return s.toString().stripTrailing();
        }

        private static String line(String name, Window w) {
            return String.format("%s    : frames %d..%d  shown %d  dropped %d  late %d  %.1f fps%n",
                    name, w.from(), w.to(), w.shown(), w.dropped(), w.late(), w.fps());
        }
    }
}
