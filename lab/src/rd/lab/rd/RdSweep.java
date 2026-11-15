package lab.rd;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Rate-distortion sweep:
 * {@code RdSweep <clip> [--frames 120] [--start 0] [--n1 0:7] [--n2 0:7] [--threads T] [--out file.csv] [--append]}.
 *
 * <p>Loads the segment once (labels and vectors do not depend on n), then codes it at every
 * (n1, n2) pair in all three modes, pairs in parallel. One CSV row per pair and mode: exact file
 * size, bitrate and mean per-frame PSNR (all / foreground / background). Writes no .cmp files.
 */
public final class RdSweep {

    static final String USAGE = "usage: RdSweep <clip> [--frames 120] [--start 0] [--n1 0:7] [--n2 0:7]"
            + " [--threads T] [--out lab/out/rd/rd_<clip>.csv] [--append]";
    static final String CSV_HEADER = "clip,start,frames,seconds,n1,n2,mode,mode_name,file_bytes,file_mb,mbps,psnr_all,psnr_fg,psnr_bg";

    private RdSweep() {
    }

    public static void main(String[] a) throws Exception {
        if (a.length < 1) {
            System.err.println(USAGE);
            System.exit(2);
        }
        Path clip = Path.of(a[0]);
        int frames = 120;
        int start = 0;
        int[] n1s = RdCore.range("0:7");
        int[] n2s = RdCore.range("0:7");
        int threads = Runtime.getRuntime().availableProcessors();
        Path out = null;
        boolean append = false;
        for (int i = 1; i < a.length; i++) {
            switch (a[i]) {
                case "--frames" -> frames = Integer.parseInt(a[++i]);
                case "--start" -> start = Integer.parseInt(a[++i]);
                case "--n1" -> n1s = RdCore.range(a[++i]);
                case "--n2" -> n2s = RdCore.range(a[++i]);
                case "--threads" -> threads = Integer.parseInt(a[++i]);
                case "--out" -> out = Path.of(a[++i]);
                case "--append" -> append = true;
                default -> {
                    System.err.println("unknown option " + a[i] + "\n" + USAGE);
                    System.exit(2);
                }
            }
        }
        if (out == null) {
            out = Path.of("lab/out/rd/rd_" + RdCore.stem(clip) + ".csv");
        }
        long t0 = System.nanoTime();
        RdCore.Segment seg = RdCore.load(clip, start, frames, true);
        System.out.printf(Locale.ROOT, "loaded %s frames %d..%d (%d), labels + vectors in %.1f s%n",
                RdCore.stem(clip), start, start + seg.frames() - 1, seg.frames(), (System.nanoTime() - t0) / 1e9);
        List<String> rows = sweep(seg, RdCore.stem(clip), n1s, n2s, threads);
        write(out, rows, append);
        System.out.printf(Locale.ROOT, "%d pairs x %d modes -> %s  (wall %.1f s, %d threads)%n",
                n1s.length * n2s.length, RdCore.MODES, out, (System.nanoTime() - t0) / 1e9, threads);
    }

    static List<String> sweep(RdCore.Segment seg, String clip, int[] n1s, int[] n2s, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<RdCore.ModeResult[]>> jobs = new ArrayList<>();
            for (int n1 : n1s) {
                for (int n2 : n2s) {
                    jobs.add(pool.submit(() -> RdCore.evaluate(seg, n1, n2)));
                }
            }
            List<String> rows = new ArrayList<>();
            int j = 0;
            for (int n1 : n1s) {
                for (int n2 : n2s) {
                    RdCore.ModeResult[] r = jobs.get(j++).get(); // fixed order: output never depends on threads
                    for (RdCore.ModeResult m : r) {
                        rows.add(row(clip, seg, n1, n2, m));
                    }
                    System.out.printf(Locale.ROOT, "(%d,%d)  base %.1f MB fg %.2f bg %.2f | rle %.1f MB | mc %.1f MB fg %.2f bg %.2f%n",
                            n1, n2, r[0].mb(), r[0].psnrFg(), r[0].psnrBg(), r[1].mb(), r[2].mb(), r[2].psnrFg(), r[2].psnrBg());
                }
            }
            return rows;
        } finally {
            pool.shutdown();
        }
    }

    static String row(String clip, RdCore.Segment seg, int n1, int n2, RdCore.ModeResult m) {
        return String.format(Locale.ROOT, "%s,%d,%d,%.4f,%d,%d,%d,%s,%d,%.4f,%.4f,%.3f,%.3f,%.3f",
                clip, seg.start, m.frames(), m.seconds(), n1, n2, m.mode(), RdCore.MODE_NAME[m.mode()], m.fileBytes(),
                m.mb(), m.mbps(), m.psnrAll(), m.psnrFg(), m.psnrBg());
    }

    static void write(Path out, List<String> rows, boolean append) throws IOException {
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        boolean header = !append || !Files.exists(out);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardOpenOption.CREATE,
                append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING))) {
            if (header) {
                w.println(CSV_HEADER);
            }
            rows.forEach(w::println);
        }
    }
}
