package codec.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DecoderMainTest {

    @TempDir
    Path dir;

    @Test
    void cmpOnly() throws IOException {
        Path cmp = Files.createFile(dir.resolve("a.cmp"));
        DecoderMain.Args a = DecoderMain.parse(new String[] {cmp.toString()});
        assertEquals(cmp, a.cmp());
        assertNull(a.original());
    }

    @Test
    void cmpWithOriginalForSideBySide() throws IOException {
        Path cmp = Files.createFile(dir.resolve("a.cmp"));
        Path mp4 = Files.createFile(dir.resolve("a.mp4"));
        assertEquals(mp4, DecoderMain.parse(new String[] {cmp.toString(), mp4.toString()}).original());
    }

    @Test
    void badArgumentsRejected() {
        assertThrows(IllegalArgumentException.class, () -> DecoderMain.parse(new String[] {}));
        assertThrows(IllegalArgumentException.class,
                () -> DecoderMain.parse(new String[] {dir.resolve("missing.cmp").toString()}));
    }

    @Test
    void benchFlagParsed() throws IOException {
        Path cmp = Files.createFile(dir.resolve("b.cmp"));
        DecoderMain.Bench b = DecoderMain.parseBench(new String[] {cmp.toString(), "--bench", "--workers", "1,2,6"});
        assertEquals(java.util.List.of(1, 2, 6), b.workers());
        assertEquals(1, DecoderMain.parseBench(new String[] {cmp.toString(), "--bench"}).workers().size());
        assertThrows(IllegalArgumentException.class,
                () -> DecoderMain.parseBench(new String[] {cmp.toString(), "--bench", "--workers", "0"}));
        assertThrows(IllegalArgumentException.class,
                () -> DecoderMain.parseBench(new String[] {cmp.toString(), "--bench", "extra"}));
    }

    @Test
    void hiddenWorkersOptionParsed() throws IOException {
        Path cmp = Files.createFile(dir.resolve("w.cmp"));
        Path mp4 = Files.createFile(dir.resolve("w.mp4"));
        assertEquals(0, DecoderMain.parse(new String[] {cmp.toString()}).workers());
        DecoderMain.Args a = DecoderMain.parse(new String[] {cmp.toString(), "--workers", "4"});
        assertEquals(4, a.workers());
        assertNull(a.original());
        DecoderMain.Args b = DecoderMain.parse(new String[] {cmp.toString(), mp4.toString(), "--workers", "6"});
        assertEquals(mp4, b.original());
        assertEquals(6, b.workers());
        assertThrows(IllegalArgumentException.class,
                () -> DecoderMain.parse(new String[] {cmp.toString(), "--workers", "0"}));
        assertThrows(IllegalArgumentException.class,
                () -> DecoderMain.parse(new String[] {cmp.toString(), mp4.toString(), mp4.toString()}));
    }
}
