package lab.rd;

import codec.common.Quant;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The streaming story:
 * {@code RateControl <clip> n1 --target-mbps X [--mode 0|1|2|all] [--segments 3] [--seg-frames 30] [--threads T] [--full]}.
 *
 * <p>Keeps the foreground exponent n1 fixed (the viewer looks at the moving subject, so its quality
 * is protected) and finds the SMALLEST background exponent n2 (finest background) whose bitrate
 * fits the budget. Bitrate falls as n2 rises, so a binary search over n2 in n1..10 needs at most
 * 4 probes. n2 never goes below n1: the background is never coded finer than the foreground.
 *
 * <p>Each probe codes a few short segments spread over the clip (default 3 x 30 frames at 20, 50
 * and 80% of its length) instead of the whole clip, the way a live encoder can only look a little
 * ahead. {@code --full} then codes the whole clip at the chosen n2 to show how close the estimate was.
 * Bitrate = bytes x 8 / seconds of video, in megabits per second (1 Mbps = 10^6 bit/s), counting
 * per-frame prefixes and footer entries but not the one 48-byte header.
 */
public final class RateControl {

    static final String USAGE = "usage: RateControl <clip> n1 --target-mbps X [--mode 0|1|2|all]"
            + " [--segments 3] [--seg-frames 30] [--threads T] [--full]";

    private RateControl() {
    }

    /** Pooled result of one n2 over all segments, per mode. */
    record Probe(int n2, double[] mbps, double[] fg, double[] bg, double[] all) {
    }

    public static void main(String[] a) throws Exception {
        if (a.length < 4) {
            System.err.println(USAGE);
            System.exit(2);
        }
        Path clip = Path.of(a[0]);
        int n1 = Integer.parseInt(a[1]);
        double target = Double.NaN;
        String mode = "all";
        int segments = 3;
        int segFrames = 30;
        int threads = Runtime.getRuntime().availableProcessors();
        boolean full = false;
        for (int i = 2; i < a.length; i++) {
            switch (a[i]) {
                case "--target-mbps" -> target = Double.parseDouble(a[++i]);
                case "--mode" -> mode = a[++i];
                case "--segments" -> segments = Integer.parseInt(a[++i]);
                case "--seg-frames" -> segFrames = Integer.parseInt(a[++i]);
                case "--threads" -> threads = Integer.parseInt(a[++i]);
                case "--full" -> full = true;
                default -> {
                    System.err.println("unknown option " + a[i] + "\n" + USAGE);
                    System.exit(2);
                }
            }
        }
        if (Double.isNaN(target) || target <= 0 || n1 < Quant.MIN_N || n1 > Quant.MAX_N) {
            System.err.println(USAGE);
            System.exit(2);
        }
        int[] modes = mode.equals("all") ? new int[] {0, 1, 2} : new int[] {Integer.parseInt(mode)};
        run(clip, n1, target, modes, segments, segFrames, threads, full);
    }

    static void run(Path clip, int n1, double target, int[] modes, int segments, int segFrames, int threads,
                    boolean full) throws Exception {
        long t0 = System.nanoTime();
        int total = RdCore.countFrames(clip);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<RdCore.Segment> segs = loadSegments(clip, total, segments, segFrames, pool);
            StringBuilder where = new StringBuilder();
            for (RdCore.Segment s : segs) {
                where.append(String.format(Locale.ROOT, " %d..%d", s.start, s.start + s.frames() - 1));
            }
            System.out.printf(Locale.ROOT, "%s: %d frames at %.2f fps; n1 = %d fixed; budget %.2f Mbps; probe segments%s%n",
                    RdCore.stem(clip), total, segs.get(0).info.fps(), n1, target, where);
            TreeMap<Integer, Probe> cache = new TreeMap<>();
            int[] chosen = new int[RdCore.MODES];
            for (int m : modes) {
                chosen[m] = search(segs, n1, target, m, cache, pool);
            }
            report(clip, n1, target, modes, chosen, cache);
            if (full) {
                fullCheck(clip, n1, modes, chosen);
            }
            System.out.printf(Locale.ROOT, "wall %.1f s%n", (System.nanoTime() - t0) / 1e9);
        } finally {
            pool.shutdown();
        }
    }

    /** Segments of segFrames frames centred on evenly spread points (20/50/80% for 3). */
    static List<RdCore.Segment> loadSegments(Path clip, int total, int count, int want, ExecutorService pool)
            throws Exception {
        int len = Math.min(want, total);
        List<Future<RdCore.Segment>> jobs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double at = count == 1 ? 0.5 : 0.2 + 0.6 * i / (count - 1);
            int start = (int) Math.round(at * (total - len));
            jobs.add(pool.submit(() -> RdCore.load(clip, start, len, true)));
        }
        List<RdCore.Segment> segs = new ArrayList<>();
        for (Future<RdCore.Segment> f : jobs) {
            segs.add(f.get());
        }
        return segs;
    }

    /** Smallest n2 in n1..MAX_N with bitrate <= target, or -1 if even MAX_N does not fit. */
    static int search(List<RdCore.Segment> segs, int n1, double target, int mode, TreeMap<Integer, Probe> cache,
                      ExecutorService pool) throws Exception {
        if (probe(segs, n1, Quant.MAX_N, cache, pool).mbps[mode] > target) {
            return -1;
        }
        int lo = n1;
        int hi = Quant.MAX_N; // invariant: hi fits
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            if (probe(segs, n1, mid, cache, pool).mbps[mode] <= target) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return hi;
    }

    static Probe probe(List<RdCore.Segment> segs, int n1, int n2, TreeMap<Integer, Probe> cache,
                       ExecutorService pool) throws Exception {
        Probe hit = cache.get(n2);
        if (hit != null) {
            return hit;
        }
        List<Future<RdCore.ModeResult[]>> jobs = new ArrayList<>();
        for (RdCore.Segment s : segs) {
            jobs.add(pool.submit(() -> RdCore.evaluate(s, n1, n2)));
        }
        double[] mbps = new double[RdCore.MODES];
        double[] fg = new double[RdCore.MODES];
        double[] bg = new double[RdCore.MODES];
        double[] all = new double[RdCore.MODES];
        double seconds = 0;
        int frames = 0;
        long[] bits = new long[RdCore.MODES];
        for (int i = 0; i < segs.size(); i++) {
            RdCore.ModeResult[] r = jobs.get(i).get();
            seconds += r[0].seconds();
            frames += r[0].frames();
            for (int m = 0; m < RdCore.MODES; m++) {
                // Bits of the frames themselves; the one 48-byte header is not part of a stream's rate.
                bits[m] += 8 * (r[m].fileBytes() - codec.common.CmpFormat.HEADER_BYTES);
                fg[m] += r[m].psnrFg() * r[m].frames(); // frame-weighted mean of per-frame PSNR
                bg[m] += r[m].psnrBg() * r[m].frames();
                all[m] += r[m].psnrAll() * r[m].frames();
            }
        }
        for (int m = 0; m < RdCore.MODES; m++) {
            mbps[m] = bits[m] / seconds / 1e6;
            fg[m] /= frames;
            bg[m] /= frames;
            all[m] /= frames;
        }
        Probe p = new Probe(n2, mbps, fg, bg, all);
        cache.put(n2, p);
        System.out.printf(Locale.ROOT, "  probe n2=%2d: base %7.2f  rle %7.2f  mc %7.2f Mbps%n", n2, mbps[0], mbps[1], mbps[2]);
        return p;
    }

    static void report(Path clip, int n1, double target, int[] modes, int[] chosen, TreeMap<Integer, Probe> cache)
            throws IOException {
        System.out.println("probes (each n2 coded once; all three modes come from the same pass):");
        System.out.println("  n2 | mode               |   Mbps | fits | PSNR fg / bg / all (dB)");
        for (Probe p : cache.values()) {
            for (int m : modes) {
                System.out.printf(Locale.ROOT, "  %2d | %-18s | %6.2f | %-4s | %.2f / %.2f / %.2f%n", p.n2, RdCore.MODE_NAME[m],
                        p.mbps[m], p.mbps[m] <= target ? "yes" : "no", p.fg[m], p.bg[m], p.all[m]);
            }
        }
        System.out.println("decision:");
        List<String> csv = new ArrayList<>();
        for (int m : modes) {
            if (chosen[m] < 0) {
                System.out.printf(Locale.ROOT, "  %-18s does not fit %.2f Mbps even at n2 = %d (%.2f Mbps): raise n1 or the budget%n",
                        RdCore.MODE_NAME[m], target, Quant.MAX_N, cache.get(Quant.MAX_N).mbps[m]);
                csv.add(String.format(Locale.ROOT, "%s,%d,%.3f,%d,%s,,,,,", RdCore.stem(clip), n1, target, m, RdCore.MODE_NAME[m]));
                continue;
            }
            Probe p = cache.get(chosen[m]);
            String finer = chosen[m] == n1 ? "budget not binding: n2 = n1 already fits"
                    : String.format(Locale.ROOT, "n2 = %d needs %.2f Mbps", chosen[m] - 1, cache.get(chosen[m] - 1).mbps[m]);
            System.out.printf(Locale.ROOT, "  %-18s n2 = %d  -> %.2f Mbps (budget %.2f), fg %.2f dB, bg %.2f dB  [%s]%n",
                    RdCore.MODE_NAME[m], chosen[m], p.mbps[m], target, p.fg[m], p.bg[m], finer);
            csv.add(String.format(Locale.ROOT, "%s,%d,%.3f,%d,%s,%d,%.3f,%.3f,%.3f,%.3f", RdCore.stem(clip), n1, target, m,
                    RdCore.MODE_NAME[m], chosen[m], p.mbps[m], p.fg[m], p.bg[m], p.all[m]));
        }
        Path out = Path.of("lab/out/rd/ratecontrol.csv");
        Files.createDirectories(out.getParent());
        boolean header = !Files.exists(out);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            if (header) {
                w.println("clip,n1,target_mbps,mode,mode_name,n2,est_mbps,psnr_fg,psnr_bg,psnr_all");
            }
            csv.forEach(w::println);
        }
        System.out.println("appended to " + out);
    }

    /** Codes the whole clip at each chosen n2 and compares with the segment estimate. */
    static void fullCheck(Path clip, int n1, int[] modes, int[] chosen) throws IOException {
        RdCore.Segment all = RdCore.load(clip, 0, Integer.MAX_VALUE, true);
        TreeMap<Integer, RdCore.ModeResult[]> done = new TreeMap<>();
        for (int m : modes) {
            if (chosen[m] < 0) {
                continue;
            }
            RdCore.ModeResult[] r = done.get(chosen[m]);
            if (r == null) {
                r = RdCore.evaluate(all, n1, chosen[m]);
                done.put(chosen[m], r);
            }
            RdCore.ModeResult x = r[m];
            System.out.printf(Locale.ROOT, "  full clip %-18s (%d,%d): %.1f MB, %.2f Mbps, fg %.2f dB, bg %.2f dB%n",
                    RdCore.MODE_NAME[m], n1, chosen[m], x.mb(), x.mbps(), x.psnrFg(), x.psnrBg());
        }
    }
}
