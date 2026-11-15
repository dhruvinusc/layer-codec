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
import java.util.ArrayDeque;
import java.util.List;

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
    private record Pending(Frame padded, MotionField mv) {
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
            ArrayDeque<Pending> waiting = new ArrayDeque<>();
            int[] prevY = null;
            MotionField prevField = null;
            try (CmpFormat.Writer out = new CmpFormat.Writer(a.output(), h)) {
                Frame f = Frame.allocate(info.width(), info.height());
                int read = 0;
                while (read < a.maxFrames() && in.read(f)) {
                    read++;
                    Frame p = Pad.toMacroblocks(f);
                    int[] y = ColorConv.luma(p);
                    MotionField mv = null;
                    if (prevY != null) {
                        mv = MotionEstimator.hierarchical(y, prevY, info.paddedW(), info.paddedH(),
                                MotionEstimator.HierParams.DEFAULT, prevField, 0, 0).field();
                        prevField = mv;
                    }
                    prevY = y;
                    waiting.add(new Pending(p, mv));
                    write(out, h, st, waiting, seg.push(y), psnr, stats);
                }
                write(out, h, st, waiting, seg.finish(), psnr, stats);
                if (!waiting.isEmpty()) { // 1-frame clip: no motion, all background (as myencoder)
                    write(out, h, st, waiting, List.of(new Labels(info.mbCols(), info.mbRows(),
                            new byte[info.mbCols() * info.mbRows()])), psnr, stats);
                }
            }
            double wall = (System.nanoTime() - t0) / 1e9;
            return String.format("mc encode: %s -> %s  frames %d  n1 %d  n2 %d  size %.1f MB  "
                    + "I frames %d avg %.2f MB raw, P frames %d avg %.2f MB raw  wall %.1f s%n%s",
                    a.input().getFileName(), a.output().getFileName(), stats.frames, a.n1(), a.n2(),
                    Files.size(a.output()) / 1e6, stats.iCount, stats.iBytes / 1e6 / Math.max(1, stats.iCount),
                    stats.pCount, stats.pBytes / 1e6 / Math.max(1, stats.pCount), wall, psnr.summary());
        }
    }

    private static final class Stats {
        int frames;
        int iCount;
        int pCount;
        long iBytes;
        long pBytes;
    }

    private static void write(CmpFormat.Writer out, Header h, McCodec.State st, ArrayDeque<Pending> waiting,
                              List<Labels> ready, Psnr psnr, Stats stats) throws IOException {
        for (Labels lab : ready) {
            Pending p = waiting.poll();
            int k = stats.frames++;
            byte[] types = CmpFormat.blockTypes(lab, h.blocksX(), h.blocksY());
            byte[] payload = McCodec.encode(h, st, p.padded(), types, p.mv(), k);
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
