package codec.tools;

import codec.common.CmpFormat;
import codec.common.Types;
import codec.common.VideoIO;
import codec.decoder.DecodeCore;
import java.nio.file.Path;

/**
 * PSNR between original and decoded frames, overall and separately over foreground and background
 * pixels (03 D10). A pixel's layer is the block_type of the 8x8 block it lies in. Error is summed
 * over R, G and B. PSNR = 10 log10(255^2 / MSE); identical frames are reported as {@link #CAP}.
 *
 * <p>Two numbers per layer: the mean of per-frame PSNR (what RESULTS reports) and the PSNR of the
 * pooled error over all frames.
 *
 * <p>CLI: {@code Psnr <original.mp4> <file.cmp>}.
 */
public final class Psnr {

    /** Reported value for zero error, so averages stay finite. */
    public static final double CAP = 99.0;

    /** Layer index for the arrays below. */
    public static final int ALL = 0;
    public static final int FG = 1;
    public static final int BG = 2;

    private final long[] sse = new long[3];
    private final long[] count = new long[3];
    private final double[] sumFramePsnr = new double[3];
    private final int[] framesWithLayer = new int[3];
    private final double[] last = new double[3];
    private int frames;

    public static double psnr(long sse, long n) {
        if (n == 0) {
            return Double.NaN;
        }
        if (sse == 0) {
            return CAP;
        }
        return Math.min(CAP, 10 * Math.log10(255.0 * 255.0 * n / sse));
    }

    /** Adds one frame pair. {@code types} is the 8x8 block_type map over the padded grid. */
    public void add(Types.Frame orig, Types.Frame dec, byte[] types, int blocksX) {
        if (orig.width() != dec.width() || orig.height() != dec.height()) {
            throw new IllegalArgumentException("frame sizes differ");
        }
        long[] s = new long[3];
        long[] n = new long[3];
        int w = orig.width();
        for (int y = 0; y < orig.height(); y++) {
            int rowBlocks = (y / Types.BLK) * blocksX;
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int dr = orig.r()[i] - dec.r()[i];
                int dg = orig.g()[i] - dec.g()[i];
                int db = orig.b()[i] - dec.b()[i];
                int e = dr * dr + dg * dg + db * db;
                int layer = types[rowBlocks + x / Types.BLK] == 1 ? FG : BG;
                s[layer] += e;
                n[layer] += 3;
            }
        }
        s[ALL] = s[FG] + s[BG];
        n[ALL] = n[FG] + n[BG];
        for (int k = 0; k < 3; k++) {
            sse[k] += s[k];
            count[k] += n[k];
            last[k] = psnr(s[k], n[k]);
            if (n[k] > 0) {
                sumFramePsnr[k] += last[k];
                framesWithLayer[k]++;
            }
        }
        frames++;
    }

    public int frames() {
        return frames;
    }

    /** PSNR of the most recent frame for a layer (NaN if that layer was absent). */
    public double lastFrame(int layer) {
        return last[layer];
    }

    /** Mean of per-frame PSNR over frames where the layer exists. */
    public double meanFramePsnr(int layer) {
        return framesWithLayer[layer] == 0 ? Double.NaN : sumFramePsnr[layer] / framesWithLayer[layer];
    }

    /** PSNR of the error pooled over all frames. */
    public double pooledPsnr(int layer) {
        return psnr(sse[layer], count[layer]);
    }

    public String summary() {
        return String.format("frames %d | mean PSNR all %.2f fg %.2f bg %.2f dB | pooled all %.2f fg %.2f bg %.2f dB",
                frames, meanFramePsnr(ALL), meanFramePsnr(FG), meanFramePsnr(BG),
                pooledPsnr(ALL), pooledPsnr(FG), pooledPsnr(BG));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: Psnr <original.mp4> <file.cmp>");
            System.exit(2);
        }
        Psnr acc = new Psnr();
        try (VideoIO.Reader video = VideoIO.open(Path.of(args[0]));
             CmpFormat.Reader cmp = new CmpFormat.Reader(Path.of(args[1]))) {
            CmpFormat.Header h = cmp.header();
            DecodeCore.Scratch s = new DecodeCore.Scratch(h);
            for (int k = 0; k < cmp.frameCount(); k++) {
                Types.Frame orig = video.read();
                if (orig == null) {
                    System.err.println("original ended after " + k + " frames");
                    break;
                }
                DecodeCore.Decoded d = DecodeCore.decode(h, cmp.payload(k), s);
                acc.add(orig, d.frame(), d.types(), h.blocksX());
            }
        }
        System.out.println(acc.summary());
    }
}
