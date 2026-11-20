package codec.tools;

import codec.common.CmpFormat;
import codec.common.CmpFormat.Header;
import codec.common.RunLength;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * P4-B tool: {@code CmpRecode in.cmp out.cmp} rewrites a baseline (mode 0) file as mode 1
 * (zigzag + run-length), frame by frame, with the SAME quantized coefficients. So the decoded
 * pictures are identical and the file sizes and decode speeds can be compared fairly. Until
 * wire-up gives myencoder a mode option, this is how mode-1 files are made.
 */
public final class CmpRecode {

    private CmpRecode() {
    }

    public static void main(String[] a) throws IOException {
        if (a.length != 2) {
            System.err.println("usage: CmpRecode in.cmp out.cmp   (baseline -> mode 1 zigzag + run-length)");
            System.exit(2);
        }
        System.out.println(recode(Path.of(a[0]), Path.of(a[1])));
    }

    /** Recodes {@code in} to {@code out}; returns a one-line size summary. */
    static String recode(Path in, Path out) throws IOException {
        try (CmpFormat.Reader r = new CmpFormat.Reader(in)) {
            Header h = r.header();
            if (h.mode() != CmpFormat.MODE_BASELINE) {
                throw new IOException(in.getFileName() + " is mode " + h.mode() + ", expected 0 (baseline)");
            }
            int flags = (h.flags() & ~CmpFormat.MODE_MASK) | (CmpFormat.MODE_RLE << CmpFormat.MODE_SHIFT);
            Header h1 = new Header(flags, h.width(), h.height(), h.paddedW(), h.paddedH(), h.fpsNum(),
                    h.fpsDen(), 0, h.n1(), h.n2(), 0);
            byte[] types = new byte[h.blockCount()];
            short[] coef = new short[h.blockCount() * CmpFormat.COEF_PER_BLOCK];
            long rawBaseline = 0;
            long rawRle = 0;
            try (CmpFormat.Writer w = new CmpFormat.Writer(out, h1)) {
                for (int k = 0; k < r.frameCount(); k++) {
                    CmpFormat.decodeBaseline(h, r.payload(k), types, coef);
                    byte[] rle = RunLength.encode(h1, types, coef);
                    rawBaseline += h.baselineRawBytes();
                    rawRle += rle.length;
                    w.writePayload(rle);
                }
            }
            long inSize = Files.size(in);
            long outSize = Files.size(out);
            return String.format("recode   : %s -> %s  frames %d  raw %.2f -> %.2f MB/frame  file %.1f -> %.1f MB"
                    + "  (%.2fx smaller)", in.getFileName(), out.getFileName(), r.frameCount(),
                    rawBaseline / 1e6 / r.frameCount(), rawRle / 1e6 / r.frameCount(), inSize / 1e6,
                    outSize / 1e6, (double) inSize / outSize);
        }
    }
}
