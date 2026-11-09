package codec.encoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncoderMainTest {

    @TempDir
    Path dir;

    private Path clip() throws IOException {
        return Files.createFile(dir.resolve("input_video.mp4"));
    }

    @Test
    void validArgsAreParsedAndOutputIsDerived() throws IOException {
        Path in = clip();
        EncoderMain.Args a = EncoderMain.parse(new String[] {in.toString(), "2", "5"});
        assertEquals(2, a.n1());
        assertEquals(5, a.n2());
        assertEquals(dir.resolve("input_video.cmp"), a.output());
    }

    @Test
    void boundaryExponentsAccepted() throws IOException {
        Path in = clip();
        EncoderMain.Args a = EncoderMain.parse(new String[] {in.toString(), "0", "10"});
        assertEquals(0, a.n1());
        assertEquals(10, a.n2());
    }

    @Test
    void wrongArgumentCountRejected() {
        assertThrows(IllegalArgumentException.class, () -> EncoderMain.parse(new String[] {"a.mp4", "2"}));
    }

    @Test
    void missingInputRejected() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> EncoderMain.parse(new String[] {dir.resolve("nope.mp4").toString(), "2", "5"}));
        assertTrue(e.getMessage().contains("not found"));
    }

    @Test
    void outOfRangeAndNonIntegerExponentsRejected() throws IOException {
        String in = clip().toString();
        assertThrows(IllegalArgumentException.class, () -> EncoderMain.parse(new String[] {in, "-1", "5"}));
        assertThrows(IllegalArgumentException.class, () -> EncoderMain.parse(new String[] {in, "2", "11"}));
        var e = assertThrows(IllegalArgumentException.class, () -> EncoderMain.parse(new String[] {in, "2.5", "5"}));
        assertTrue(e.getMessage().contains("integer"));
    }

    @Test
    void cmpPathKeepsFolderAndStem() {
        assertEquals(Path.of("data", "SAL.cmp"), EncoderMain.cmpPathFor(Path.of("data", "SAL.mp4")));
        assertEquals(Path.of("noext.cmp"), EncoderMain.cmpPathFor(Path.of("noext")));
        assertEquals(Path.of("my.clip.cmp"), EncoderMain.cmpPathFor(Path.of("my.clip.mp4")));
    }
}
