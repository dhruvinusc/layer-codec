package codec.tools;

import codec.common.CmpFormat;
import codec.common.CmpFormat.Header;
import java.io.PrintStream;
import java.nio.file.Path;

/**
 * Prints a .cmp file in human-readable form, ending in the brief's own suggested text layout, so
 * a grader can map our binary file onto the format in the assignment (03 D7, checklist C5):
 *
 * <pre>
 * n1 n2
 * block_type coeff1 ... coeff64 coeff1 ... coeff64 coeff1 ... coeff64     (R, G, B)
 * </pre>
 *
 * <p>CLI: {@code CmpDump <file.cmp> [--frame f] [--blocks K]} (defaults: frame 0, first 4 blocks).
 */
public final class CmpDump {

    static final String USAGE = "usage: CmpDump <file.cmp> [--frame f] [--blocks K]";

    private CmpDump() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println(USAGE);
            System.exit(2);
        }
        int frame = 0;
        int blocks = 4;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--frame" -> frame = Integer.parseInt(args[++i]);
                case "--blocks" -> blocks = Integer.parseInt(args[++i]);
                default -> {
                    System.err.println("unknown option " + args[i] + "\n" + USAGE);
                    System.exit(2);
                }
            }
        }
        dump(Path.of(args[0]), frame, blocks, System.out);
    }

    static void dump(Path file, int frame, int blocks, PrintStream out) throws Exception {
        try (CmpFormat.Reader r = new CmpFormat.Reader(file)) {
            Header h = r.header();
            out.printf("# file %s%n", file.getFileName());
            out.printf("# version %d, mode %d, zlib %s%n", CmpFormat.VERSION, h.mode(), h.zlib());
            out.printf("# size %dx%d, padded %dx%d, %d x %d blocks of 8x8 (%d per frame)%n",
                    h.width(), h.height(), h.paddedW(), h.paddedH(), h.blocksX(), h.blocksY(), h.blockCount());
            out.printf("# fps %d/%d = %.3f, frames %d%n", h.fpsNum(), h.fpsDen(),
                    (double) h.fpsNum() / h.fpsDen(), r.frameCount());
            printFrameTable(r, out);
            byte[] types = new byte[h.blockCount()];
            short[] coef = new short[h.blockCount() * CmpFormat.COEF_PER_BLOCK];
            r.readFrame(frame, types, coef);
            int shown = Math.min(blocks, h.blockCount());
            out.printf("# frame %d, first %d blocks in scan line order, brief's text layout:%n", frame, shown);
            out.println(h.n1() + " " + h.n2());
            for (int b = 0; b < shown; b++) {
                StringBuilder line = new StringBuilder().append(types[b]);
                for (int i = 0; i < CmpFormat.COEF_PER_BLOCK; i++) {
                    line.append(' ').append(coef[b * CmpFormat.COEF_PER_BLOCK + i]);
                }
                out.println(line);
            }
        }
    }

    /** Frame offsets and sizes; long files show the first and last 5. */
    private static void printFrameTable(CmpFormat.Reader r, PrintStream out) throws Exception {
        out.println("# frame  offset  stored_bytes  raw_bytes  ratio");
        int n = r.frameCount();
        for (int k = 0; k < n; k++) {
            if (n > 10 && k == 5) {
                out.printf("# ... %d frames ...%n", n - 10);
                k = n - 5;
            }
            int[] len = r.lengths(k);
            out.printf("# %5d  %d  %d  %d  %.2fx%n", k, r.offset(k), len[0], len[1], (double) len[1] / len[0]);
        }
    }
}
