package codec.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Golden test (P4 requirement): the BASELINE output (mode 0) must stay byte-identical whatever
 * extra-credit code is added. The two SHA-256 values were computed on commit 78da695, before any
 * P4 code existed. If one of these fails, the default .cmp changed: that is a spec violation
 * (00 section 3), not a test to update.
 */
class GoldenBaselineTest {

    @TempDir
    Path dir;

    static final String GOLDEN_PAYLOAD = "909ace809184900b3f06a96b511a2300c24b34eefa7e940291cdeb0b7f4dbbf9";
    static final String GOLDEN_FILE = "ce7bcdaf8ef11d056136c5063b60015078680b70c46547be93e3535ca043dc92";

    /** Deterministic 72x40 picture (not a multiple of 16: padding exercised), different per channel. */
    static Types.Frame picture(int seed) {
        Types.Frame f = Types.Frame.allocate(72, 40);
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 72; x++) {
                int i = y * 72 + x;
                f.r()[i] = (x * 7 + y * 3 + seed * 11) & 0xFF;
                f.g()[i] = (int) (128 + 100 * Math.sin((x + seed) * 0.4) * Math.cos(y * 0.3));
                f.b()[i] = (x * y + seed) & 0xFF;
            }
        }
        return f;
    }

    /** Checkerboard of macroblocks, as CodecHarness uses. */
    static Types.Labels checker(int cols, int rows) {
        byte[] id = new byte[cols * rows];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                id[r * cols + c] = (byte) ((c + r) % 2 == 0 ? 1 : 0);
            }
        }
        return new Types.Labels(cols, rows, id);
    }

    static CmpFormat.Header header() {
        return new CmpFormat.Header(CmpFormat.FLAG_ZLIB, 72, 40, 80, 48, 30, 1, 0, 2, 5, 0);
    }

    static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    @Test
    void baselinePayloadIsByteIdentical() throws Exception {
        byte[] raw = FrameEncoder.encode(header(), picture(0), checker(5, 3));
        assertEquals(GOLDEN_PAYLOAD, sha(raw));
    }

    @Test
    void baselineFileIsByteIdentical() throws Exception {
        Path p = dir.resolve("g.cmp");
        try (CmpFormat.Writer w = new CmpFormat.Writer(p, header())) {
            for (int f = 0; f < 3; f++) {
                w.writePayload(FrameEncoder.encode(header(), picture(f), checker(5, 3)));
            }
        }
        assertEquals(GOLDEN_FILE, sha(Files.readAllBytes(p)));
    }
}
