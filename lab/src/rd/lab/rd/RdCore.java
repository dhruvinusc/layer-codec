package lab.rd;

import codec.common.CmpFormat;
import codec.common.CmpFormat.Header;
import codec.common.ColorConv;
import codec.common.FrameEncoder;
import codec.common.McCodec;
import codec.common.Pad;
import codec.common.RunLength;
import codec.common.Types;
import codec.common.Types.Frame;
import codec.common.Types.Labels;
import codec.common.Types.MotionField;
import codec.common.VideoIO;
import codec.common.Zio;
import codec.decoder.DecodeCore;
import codec.encoder.GlobalMotion;
import codec.encoder.MotionEstimator;
import codec.encoder.Segmenter;
import codec.tools.Psnr;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lab L3 (Bits and Budgets): shared core for the rate-distortion tools. Read-only use of the
 * frozen codec (88fc181): nothing here changes what myencoder, mydecoder or McEncode produce.
 *
 * <p>A {@link Segment} is a short run of frames held in memory with everything that does NOT
 * depend on n1 and n2: padded frames, segmentation labels (Segmenter.Run, same settings as
 * myencoder) and the P-frame motion vectors (same search and predictor chain as McEncode). Then
 * {@link #evaluate} codes the segment at one (n1, n2) in all three .cmp modes, in memory, and
 * returns exact sizes and PSNR. No .cmp file is written, so a 64-pair sweep costs no disk.
 *
 * <p>Size is the exact byte count the real writer would produce: 48-byte header, then per frame
 * an 8-byte length prefix plus the zlib-compressed payload, then an 8-byte footer entry per frame
 * (CmpFormat D7). Mode 1 (zigzag + run-length) holds the same quantized coefficients as mode 0,
 * so its pixels and PSNR equal mode 0's (lossless re-packing, RESULTS "P4 final").
 */
public final class RdCore {

    public static final int MODES = 3;
    public static final String[] MODE_NAME = {"baseline", "zigzag+RLE", "motion-comp GOP2"};
    static final int FRAME_PREFIX = 8;
    static final int FOOTER_ENTRY = 8;

    private RdCore() {
    }

    /** Frames start..start+count-1 of one clip, with labels and P-frame vectors. */
    public static final class Segment {
        public final Types.VideoInfo info;
        public final int start;
        final byte[][] rgb;       // padded frame k as planar R, G, B bytes (a third of int memory)
        final Labels[] labels;
        final MotionField[] mv;   // null for I frames (k % GOP == 0, k counted inside the segment)

        Segment(Types.VideoInfo info, int start, byte[][] rgb, Labels[] labels, MotionField[] mv) {
            this.info = info;
            this.start = start;
            this.rgb = rgb;
            this.labels = labels;
            this.mv = mv;
        }

        public int frames() {
            return rgb.length;
        }

        public double seconds() {
            return frames() * (double) info.fpsDen() / info.fpsNum();
        }

        public Labels labels(int k) {
            return labels[k];
        }

        /** Padded frame k as a fresh Frame (callers may keep it). */
        public Frame padded(int k) {
            int n = info.paddedW() * info.paddedH();
            Frame f = Frame.allocate(info.paddedW(), info.paddedH());
            byte[] b = rgb[k];
            for (int i = 0; i < n; i++) {
                f.r()[i] = b[i] & 0xFF;
                f.g()[i] = b[n + i] & 0xFF;
                f.b()[i] = b[2 * n + i] & 0xFF;
            }
            return f;
        }
    }

    /** Frame count of a clip, counted by reading (container tags are only hints, 03 D11). */
    public static int countFrames(Path clip) throws IOException {
        try (VideoIO.Reader in = VideoIO.open(clip)) {
            Frame f = Frame.allocate(in.info().width(), in.info().height());
            int n = 0;
            while (in.read(f)) {
                n++;
            }
            return n;
        }
    }

    /**
     * Loads frames start..start+count-1 (fewer if the clip ends). Segmentation and the motion
     * vectors start fresh at the segment's first frame, exactly as McEncode does at frame 0, so
     * a segment starting at 0 reproduces {@code McEncode --frames count} byte for byte.
     */
    public static Segment load(Path clip, int start, int count, boolean withVectors) throws IOException {
        try (VideoIO.Reader in = VideoIO.open(clip)) {
            Types.VideoInfo info = in.info();
            int pw = info.paddedW();
            int ph = info.paddedH();
            Frame f = Frame.allocate(info.width(), info.height());
            for (int s = 0; s < start; s++) {
                if (!in.read(f)) {
                    throw new IOException(clip + " has only " + s + " frames, segment starts at " + start);
                }
            }
            Segmenter.Run seg = new Segmenter.Run(pw, ph, Segmenter.SegConfig.DEFAULT,
                    GlobalMotion.GmConfig.DEFAULT, MotionEstimator.HierParams.DEFAULT);
            List<byte[]> rgb = new ArrayList<>();
            List<Labels> labels = new ArrayList<>();
            List<MotionField> mv = new ArrayList<>();
            int[] prevY = null;
            MotionField prevField = null;
            while (rgb.size() < count && in.read(f)) {
                int k = rgb.size();
                Frame p = Pad.toMacroblocks(f);
                int[] y = ColorConv.luma(p);
                MotionField m = null;
                // Same rule and predictor chain as McEncode: vectors only for P frames.
                if (withVectors && prevY != null && !McCodec.isIntra(k)) {
                    m = MotionEstimator.hierarchical(y, prevY, pw, ph, MotionEstimator.HierParams.DEFAULT,
                            prevField, 0, 0).field();
                    prevField = m;
                }
                mv.add(m);
                rgb.add(pack(p));
                labels.addAll(seg.push(y));
                prevY = y;
            }
            labels.addAll(seg.finish());
            while (labels.size() < rgb.size()) { // 1-frame segment: no motion, all background (as myencoder)
                labels.add(new Labels(info.mbCols(), info.mbRows(), new byte[info.mbCols() * info.mbRows()]));
            }
            return new Segment(info, start, rgb.toArray(new byte[0][]), labels.toArray(new Labels[0]),
                    mv.toArray(new MotionField[0]));
        }
    }

    private static byte[] pack(Frame p) {
        int n = p.width() * p.height();
        byte[] b = new byte[3 * n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) p.r()[i];
            b[n + i] = (byte) p.g()[i];
            b[2 * n + i] = (byte) p.b()[i];
        }
        return b;
    }

    /** Result of one mode at one (n1, n2) on one segment. */
    public record ModeResult(int mode, long fileBytes, int frames, double seconds,
                             double psnrAll, double psnrFg, double psnrBg) {
        public double mb() {
            return fileBytes / 1e6;
        }

        public double mbps() {
            return fileBytes * 8.0 / seconds / 1e6;
        }
    }

    static Header header(Types.VideoInfo info, int mode, int n1, int n2) {
        int flags = CmpFormat.FLAG_ZLIB | (mode << CmpFormat.MODE_SHIFT);
        return new Header(flags, info.width(), info.height(), info.paddedW(), info.paddedH(),
                info.fpsNum(), info.fpsDen(), 0, n1, n2, 0);
    }

    static int stored(byte[] raw) {
        return FRAME_PREFIX + Zio.deflate(raw, 0, raw.length).length;
    }

    /** Codes the segment at (n1, n2) in all three modes. Thread-safe: all state is local. */
    public static ModeResult[] evaluate(Segment s, int n1, int n2) throws IOException {
        Types.VideoInfo info = s.info;
        Header h0 = header(info, CmpFormat.MODE_BASELINE, n1, n2);
        Header h1 = header(info, CmpFormat.MODE_RLE, n1, n2);
        Header h2 = header(info, CmpFormat.MODE_MC, n1, n2);
        DecodeCore.Scratch scratch = new DecodeCore.Scratch(h0);
        McCodec.State st = new McCodec.State(info.paddedW(), info.paddedH());
        byte[] types = new byte[h0.blockCount()];
        short[] coef = new short[h0.blockCount() * CmpFormat.COEF_PER_BLOCK];
        Psnr p0 = new Psnr();
        Psnr p2 = new Psnr();
        long[] bytes = new long[MODES];
        for (int k = 0; k < s.frames(); k++) {
            Frame p = s.padded(k);
            Frame orig = Pad.crop(p, info.width(), info.height());
            byte[] raw = FrameEncoder.encode(h0, p, s.labels[k]);
            bytes[0] += stored(raw);
            CmpFormat.decodeBaseline(h0, raw, types, coef);
            bytes[1] += stored(RunLength.encode(h1, types, coef));
            DecodeCore.Decoded d = DecodeCore.decode(h0, raw, scratch);
            p0.add(orig, d.frame(), d.types(), h0.blocksX());
            bytes[2] += stored(McCodec.encode(h2, st, p, types, s.mv[k], k));
            p2.add(orig, st.frame(info.width(), info.height()), types, h0.blocksX());
        }
        ModeResult[] out = new ModeResult[MODES];
        for (int m = 0; m < MODES; m++) {
            Psnr ps = m == 2 ? p2 : p0;
            long file = CmpFormat.HEADER_BYTES + bytes[m] + (long) FOOTER_ENTRY * s.frames();
            out[m] = new ModeResult(m, file, s.frames(), s.seconds(), ps.meanFramePsnr(Psnr.ALL),
                    ps.meanFramePsnr(Psnr.FG), ps.meanFramePsnr(Psnr.BG));
        }
        return out;
    }

    /** Parses "a:b" or "a" into an inclusive range. */
    static int[] range(String spec) {
        String[] p = spec.split(":");
        int a = Integer.parseInt(p[0]);
        int b = p.length > 1 ? Integer.parseInt(p[1]) : a;
        int[] r = new int[b - a + 1];
        for (int i = 0; i < r.length; i++) {
            r[i] = a + i;
        }
        return r;
    }

    static String stem(Path clip) {
        return clip.getFileName().toString().replaceFirst("\\.[^.]+$", "");
    }
}
