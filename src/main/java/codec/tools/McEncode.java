package codec.tools;

import codec.common.CmpFormat;
import codec.common.CmpFormat.Header;
import codec.common.ColorConv;
import codec.common.McCodec;
import codec.common.Pad;
import codec.common.Types;
import codec.common.Types.Frame;
import codec.common.Types.Labels;
import codec.common.Types.MotionField;
import codec.common.VideoIO;
import codec.encoder.GlobalMotion;
import codec.encoder.MotionEstimator;
import codec.encoder.Segmenter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * P4-A encoder: {@code McEncode input.mp4 n1 n2 [output.cmp] [--frames N]} writes a mode-2 .cmp
 * (motion-compensated residuals, an I frame every 8). Labels come from Window A's segmentation
 * (Segmenter.Run, same settings as myencoder), motion vectors from Window A's hierarchical motion
 * search on the padded Y plane (each frame against the previous ORIGINAL frame; the prediction
 * itself uses the previous RECONSTRUCTED frame, see McCodec). Window A's files are only called,
 * never changed. Until myencoder gets a "--mode mc" option at the next wire-up, this tool makes
 * mode-2 files.
 *
 * <p>It also reports PSNR per layer from its own reconstruction, which the tests prove is
 * bit-identical to what the decoder rebuilds.
 */
public final class McEncode {

    private McEncode() {
    }

    record Args(Path input, int n1, int n2, Path output, int maxFrames) {
    }

    static Args parse(String[] a) {
        if (a.length < 3) {
            throw new IllegalArgumentException("usage: McEncode input.mp4 n1 n2 [output.cmp] [--frames N]");
        }
        Path in = Path.of(a[0]);
        int n1 = Integer.parseInt(a[1]);
        int n2 = Integer.parseInt(a[2]);
        Path out = null;
        int max = Integer.MAX_VALUE;
        for (int i = 3; i < a.length; i++) {
            if (a[i].equals("--frames") && i + 1 < a.length) {
                max = Integer.parseInt(a[++i]);
            } else {
                out = Path.of(a[i]);
            }
        }
        if (out == null) {
            String name = in.getFileName().toString();
            int dot = name.lastIndexOf('.');
            out = in.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + "_mc.cmp");
        }
        return new Args(in, n1, n2, out, max);
    }

    public static void main(String[] a) throws Exception {
        try {
            System.out.println(encode(parse(a)));
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(2);
        }
    }

    /** A padded frame waiting for its labels, with its motion field against the previous frame. */
    private record Pending(Frame padded, Future<MotionField> mv) {
    }

    static String encode(Args a) throws IOException {
        long t0 = System.nanoTime();
        try (VideoIO.Reader in = VideoIO.open(a.input())) {
            Types.VideoInfo info = in.info();
            int flags = CmpFormat.FLAG_ZLIB | (CmpFormat.MODE_MC << CmpFormat.MODE_SHIFT);
            Header h = new Header(flags, info.width(), info.height(), info.paddedW(), info.paddedH(),
                    info.fpsNum(), info.fpsDen(), 0, a.n1(), a.n2(), 0);
            Segmenter.Run seg = new Segmenter.Run(info.paddedW(), info.paddedH(), Segmenter.SegConfig.DEFAULT,
                    GlobalMotion.GmConfig.DEFAULT, MotionEstimator.HierParams.DEFAULT);
            McCodec.State st = new McCodec.State(info.paddedW(), info.paddedH());
            Psnr psnr = new Psnr();
            Stats stats = new Stats();
            // Three stages, each on one thread, so their order (and the output bytes) never changes:
            // main reads frames and runs the segmenter; "me" searches the vectors for P frames
            // (its own chain of predictors); "enc" codes frames in order once their labels are out.
            ConcurrentLinkedDeque<Pending> waiting = new ConcurrentLinkedDeque<>();
            ExecutorService me = Executors.newSingleThreadExecutor();
            ExecutorService enc = Executors.newSingleThreadExecutor();
            Semaphore backlog = new Semaphore(6); // frames handed to enc but not yet coded
            MotionField[] prevField = {null};
            Future<?> last = null;
            try (CmpFormat.Writer out = new CmpFormat.Writer(a.output(), h)) {
                Frame f = Frame.allocate(info.width(), info.height());
                int[] prevY = null;
                int read = 0;
                while (read < a.maxFrames() && in.read(f)) {
                    read++;
                    Frame p = Pad.toMacroblocks(f);
                    int[] y = ColorConv.luma(p);
                    Future<MotionField> mv;
                    // Only P frames use vectors; skipping the search on I frames halves it at GOP 2.
                    if (prevY != null && !McCodec.isIntra(read - 1)) {
                        int[] cur = y;
                        int[] ref = prevY;
                        mv = me.submit(() -> {
                            MotionField m = MotionEstimator.hierarchical(cur, ref, info.paddedW(), info.paddedH(),
                                    MotionEstimator.HierParams.DEFAULT, prevField[0], 0, 0).field();
                            prevField[0] = m;
                            return m;
                        });
                    } else {
                        mv = java.util.concurrent.CompletableFuture.completedFuture(null);
                    }
                    prevY = y;
                    waiting.add(new Pending(p, mv));
                    last = newest(last, submit(enc, backlog, out, h, st, waiting, seg.push(y), psnr, stats));
                }
                last = newest(last, submit(enc, backlog, out, h, st, waiting, seg.finish(), psnr, stats));
                await(last, stats);
                if (!waiting.isEmpty()) { // 1-frame clip: no motion, all background (as myencoder)
                    last = submit(enc, backlog, out, h, st, waiting, List.of(new Labels(info.mbCols(), info.mbRows(),
                            new byte[info.mbCols() * info.mbRows()])), psnr, stats);
                    await(last, stats);
                }
            } finally {
                me.shutdownNow();
                enc.shutdownNow();
            }
            double wall = (System.nanoTime() - t0) / 1e9;
            return String.format("mc encode: %s -> %s  frames %d  n1 %d  n2 %d  size %.1f MB  "
                    + "I frames %d avg %.2f MB raw, P frames %d avg %.2f MB raw  wall %.1f s%n%s",
                    a.input().getFileName(), a.output().getFileName(), stats.frames, a.n1(), a.n2(),
                    Files.size(a.output()) / 1e6, stats.iCount, stats.iBytes / 1e6 / Math.max(1, stats.iCount),
                    stats.pCount, stats.pBytes / 1e6 / Math.max(1, stats.pCount), wall, psnr.summary());
        }
    }

    /** Hands ready labels to the coding thread; every task runs in submission order. */
    private static Future<?> submit(ExecutorService enc, Semaphore backlog, CmpFormat.Writer out, Header h,
                                    McCodec.State st, ConcurrentLinkedDeque<Pending> waiting, List<Labels> ready,
                                    Psnr psnr, Stats stats) throws IOException {
        if (ready.isEmpty()) {
            return null;
        }
        try {
            backlog.acquire(ready.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        return enc.submit(() -> {
            try {
                if (stats.error == null) { // after a failure, later frames are not coded
                    write(out, h, st, waiting, ready, psnr, stats);
                }
            } catch (Exception e) {
                stats.error = e;
            } finally {
                backlog.release(ready.size());
            }
            return null;
        });
    }

    private static Future<?> newest(Future<?> last, Future<?> next) {
        return next == null ? last : next;
    }

    /**
     * Waits for the last coding task; tasks run in order, so all earlier ones are done too. Then
     * rethrows the first failure of any task.
     */
    private static void await(Future<?> last, Stats stats) throws IOException {
        if (last != null) {
            get(last);
        }
        Throwable c = stats.error;
        if (c instanceof IOException io) {
            throw io;
        }
        if (c instanceof RuntimeException r) {
            throw r;
        }
        if (c != null) {
            throw new IOException(c);
        }
    }

    private static void get(Future<?> last) throws IOException {
        try {
            last.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException io) {
                throw io;
            }
            if (c instanceof RuntimeException r) {
                throw r;
            }
            throw new IOException(c);
        }
    }

    private static final class Stats {
        int frames;
        int iCount;
        int pCount;
        long iBytes;
        long pBytes;
        Exception error; // written and read only on the coding thread, then read after get()
    }

    private static void write(CmpFormat.Writer out, Header h, McCodec.State st, ConcurrentLinkedDeque<Pending> waiting,
                              List<Labels> ready, Psnr psnr, Stats stats) throws IOException, InterruptedException,
            ExecutionException {
        for (Labels lab : ready) {
            Pending p = waiting.poll();
            int k = stats.frames++;
            byte[] types = CmpFormat.blockTypes(lab, h.blocksX(), h.blocksY());
            byte[] payload = McCodec.encode(h, st, p.padded(), types, p.mv().get(), k);
            out.writePayload(payload);
            if (McCodec.isIntra(k)) {
                stats.iCount++;
                stats.iBytes += payload.length;
            } else {
                stats.pCount++;
                stats.pBytes += payload.length;
            }
            psnr.add(Pad.crop(p.padded(), h.width(), h.height()), st.frame(h.width(), h.height()), types, h.blocksX());
        }
    }
}
