package codec.encoder;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point for {@code myencoder input_video.mp4 n1 n2} (brief, part 1).
 *
 * <p>P0 stub: validates the arguments and prints them. Segmentation arrives in P1,
 * DCT, quantization and the .cmp writer in P2.
 */
public final class EncoderMain {

    /**
     * Largest useful exponent. For an 8x8 DCT of level-shifted 8-bit samples the DC term
     * is at most 1024 = 2^10 in magnitude, so a step above 2^10 only zeroes every block.
     */
    static final int MAX_N = 10;
    static final int MIN_N = 0;

    static final String USAGE = String.join("\n",
            "usage: myencoder input_video.mp4 n1 n2",
            "  n1  quantization exponent for FOREGROUND blocks (step 2^n1), integer " + MIN_N + " to " + MAX_N,
            "  n2  quantization exponent for BACKGROUND blocks (step 2^n2), integer " + MIN_N + " to " + MAX_N,
            "  writes input_video.cmp next to the input");

    /** A validated command line. {@code output} is derived from {@code input}, never typed by the user. */
    record Args(Path input, int n1, int n2, Path output) {}

    private EncoderMain() {
    }

    public static void main(String[] argv) {
        Args args;
        try {
            args = parse(argv);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        System.out.printf("input   : %s%n", args.input());
        System.out.printf("n1 (fg) : %d  (step %d)%n", args.n1(), 1 << args.n1());
        System.out.printf("n2 (bg) : %d  (step %d)%n", args.n2(), 1 << args.n2());
        System.out.printf("output  : %s%n", args.output());
        System.out.println("stub: segmentation (P1) and compression (P2) not implemented yet");
    }

    /** Parses and validates; throws IllegalArgumentException with a message meant for the user. */
    static Args parse(String[] argv) {
        if (argv.length != 3) {
            throw new IllegalArgumentException("expected 3 arguments, got " + argv.length);
        }
        Path input = Path.of(argv[0]);
        if (!Files.isRegularFile(input)) {
            throw new IllegalArgumentException("input file not found: " + input);
        }
        int n1 = parseExponent("n1", argv[1]);
        int n2 = parseExponent("n2", argv[2]);
        return new Args(input, n1, n2, cmpPathFor(input));
    }

    static int parseExponent(String name, String text) {
        int n;
        try {
            n = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer, got '" + text + "'");
        }
        if (n < MIN_N || n > MAX_N) {
            throw new IllegalArgumentException(name + " must be in " + MIN_N + ".." + MAX_N + ", got " + n);
        }
        return n;
    }

    /** {@code dir/input_video.mp4} becomes {@code dir/input_video.cmp} (brief: same base name). */
    static Path cmpPathFor(Path input) {
        String file = input.getFileName().toString();
        int dot = file.lastIndexOf('.');
        String stem = dot > 0 ? file.substring(0, dot) : file;
        return input.resolveSibling(stem + ".cmp");
    }
}
