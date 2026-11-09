package codec.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import codec.common.Types.Frame;
import codec.common.Types.VideoInfo;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoIOTest {

    // Trimmed real ffprobe output for video2.mp4 (FFmpeg 4.4 prints both rotation forms).
    private static final String VIDEO2 = String.join("\n",
            "codec_name=h264", "width=848", "height=480", "r_frame_rate=30/1",
            "avg_frame_rate=57450/1913", "nb_frames=383", "TAG:rotate=90",
            "side_data_type=Display Matrix", "displaymatrix=", "rotation=-90");

    private static final String STAIRS = String.join("\n",
            "width=1920", "height=1080", "r_frame_rate=30/1", "nb_frames=346", "rotation=-180");

    @Test
    void video2IsPortraitAfterRotation() {
        VideoInfo v = VideoIO.parseProbe(VIDEO2);
        assertEquals(new VideoInfo(480, 848, 480, 848, 30, 1, 383, 90), v);
    }

    @Test
    void stairsPadsTo1088AndRotates180() {
        VideoInfo v = VideoIO.parseProbe(STAIRS);
        assertEquals(1920, v.width());
        assertEquals(1080, v.height());
        assertEquals(1088, v.paddedH());
        assertEquals(180, v.rotationCw());
        assertEquals(120, v.mbCols());
        assertEquals(68, v.mbRows());
    }

    @Test
    void rotationFromEitherFfmpegVersion() {
        assertEquals(90, VideoIO.rotationCw("-90", null));   // FFmpeg 5+: side data only
        assertEquals(90, VideoIO.rotationCw(null, "90"));    // FFmpeg 4: legacy tag only
        assertEquals(270, VideoIO.rotationCw("90", null));
        assertEquals(180, VideoIO.rotationCw("-180", "180"));
        assertEquals(0, VideoIO.rotationCw(null, null));
        assertThrows(IllegalArgumentException.class, () -> VideoIO.rotationCw("-45", null));
    }

    @Test
    void rotationFilters() {
        assertEquals("", VideoIO.rotationFilter(0));
        assertEquals("transpose=clock", VideoIO.rotationFilter(90));
        assertEquals("hflip,vflip", VideoIO.rotationFilter(180));
        assertEquals("transpose=cclock", VideoIO.rotationFilter(270));
    }

    @Test
    void unpackHandlesBytesAbove127() {
        // The signed-byte trap: (byte) 200 is -56 in Java unless masked.
        byte[] src = {(byte) 200, (byte) 128, (byte) 255, 0, 127, (byte) 129};
        int[] r = new int[2];
        int[] g = new int[2];
        int[] b = new int[2];
        VideoIO.unpackRgb24(src, r, g, b);
        assertArrayEquals(new int[] {200, 0}, r);
        assertArrayEquals(new int[] {128, 127}, g);
        assertArrayEquals(new int[] {255, 129}, b);
    }

    @Test
    void readsSyntheticClipInRgbOrder(@TempDir Path dir) throws Exception {
        assumeTrue(ffmpegAvailable(), "ffmpeg not on PATH");
        Path clip = dir.resolve("red.mp4");
        Process p = new ProcessBuilder(VideoIO.ffmpegBin(), "-v", "error", "-f", "lavfi",
                "-i", "color=c=red:size=64x48:rate=30", "-frames:v", "5", "-c:v", "mpeg4",
                "-q:v", "2", "-pix_fmt", "yuv420p", clip.toString())
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());

        try (VideoIO.Reader reader = VideoIO.open(clip)) {
            assertEquals(64, reader.info().width());
            assertEquals(48, reader.info().height());
            Frame f = reader.read();
            int mid = 24 * 64 + 32;
            // Pure red after YUV round trip: R high, G and B low. Catches RGB/BGR swaps and sign bugs.
            assertTrue(f.r()[mid] > 200, "R=" + f.r()[mid]);
            assertTrue(f.g()[mid] < 50, "G=" + f.g()[mid]);
            assertTrue(f.b()[mid] < 50, "B=" + f.b()[mid]);
            int count = 1;
            while (reader.read() != null) {
                count++;
            }
            assertEquals(5, count);
            assertNull(reader.read());
        }
    }

    private static boolean ffmpegAvailable() {
        try {
            Process p = new ProcessBuilder(List.of(VideoIO.ffmpegBin(), "-version"))
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
