package codec.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codec.common.CmpFormat;
import codec.common.CmpFormat.Header;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CmpDumpTest {

    @Test
    void printsTheBriefsTextLayout(@TempDir Path dir) throws Exception {
        Header h = new Header(CmpFormat.FLAG_ZLIB, 30, 10, 32, 16, 30, 1, 0, 2, 5, 0); // 8 blocks
        byte[] types = {1, 0, 0, 0, 0, 0, 0, 0};
        short[] coef = new short[8 * CmpFormat.COEF_PER_BLOCK];
        coef[0] = 72;      // block 0, R DC
        coef[64] = -3;     // block 0, G DC
        coef[191] = 9;     // block 0, last B coefficient
        coef[192] = 11;    // block 1, R DC
        Path f = dir.resolve("d.cmp");
        try (CmpFormat.Writer w = new CmpFormat.Writer(f, h)) {
            w.writeFrame(types, coef);
            w.writeFrame(types, coef);
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        CmpDump.dump(f, 1, 2, new PrintStream(buf, true, StandardCharsets.UTF_8));
        List<String> data = buf.toString(StandardCharsets.UTF_8).lines().filter(l -> !l.startsWith("#")).toList();

        assertEquals("2 5", data.get(0));                 // n1 n2 first, as the brief shows
        String[] b0 = data.get(1).split(" ");
        assertEquals(1 + 3 * 64, b0.length);              // block_type + 64 R + 64 G + 64 B
        assertEquals("1", b0[0]);
        assertEquals("72", b0[1]);
        assertEquals("-3", b0[1 + 64]);
        assertEquals("9", b0[192]);
        String[] b1 = data.get(2).split(" ");
        assertEquals("0", b1[0]);
        assertEquals("11", b1[1]);
        assertEquals(3, data.size());
        assertTrue(buf.toString(StandardCharsets.UTF_8).contains("frames 2"));
    }
}
