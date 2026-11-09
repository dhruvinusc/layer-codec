package codec.decoder;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point for {@code mydecoder input_video.cmp} (brief, part 3).
 *
 * <p>The optional second argument (the original mp4) enables side-by-side comparison
 * (03 D8). It is off by default so the spec command line is unchanged.
 *
 * <p>P0 stub: validates the arguments and prints them. Decoding and playback arrive in P3.
 */
public final class DecoderMain {

    static final String USAGE = String.join("\n",
            "usage: mydecoder input_video.cmp [original_video.mp4]",
            "  plays the decoded video at its original frame rate",
            "  optional original_video.mp4 enables side-by-side comparison");

    /** A validated command line. {@code original} is null when side-by-side is not requested. */
    record Args(Path cmp, Path original) {}

    private DecoderMain() {
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
        System.out.printf("cmp      : %s%n", args.cmp());
        System.out.printf("original : %s%n", args.original() == null ? "(none)" : args.original());
        System.out.println("stub: decoding and playback (P3) not implemented yet");
    }

    static Args parse(String[] argv) {
        if (argv.length < 1 || argv.length > 2) {
            throw new IllegalArgumentException("expected 1 or 2 arguments, got " + argv.length);
        }
        Path cmp = requireFile(argv[0]);
        Path original = argv.length == 2 ? requireFile(argv[1]) : null;
        return new Args(cmp, original);
    }

    private static Path requireFile(String text) {
        Path p = Path.of(text);
        if (!Files.isRegularFile(p)) {
            throw new IllegalArgumentException("file not found: " + p);
        }
        return p;
    }
}
