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
}
