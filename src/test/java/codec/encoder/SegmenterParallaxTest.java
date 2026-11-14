package codec.encoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import codec.common.Types.Labels;
import codec.common.Types.MotionField;
import codec.encoder.GlobalMotion.Kind;
import codec.encoder.GlobalMotion.Model;
import codec.encoder.Segmenter.SegConfig;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The optional parallax filter (P1.9, off by default pending a decision). */
class SegmenterParallaxTest {

    static final int COLS = 30;
    static final int ROWS = 16;

    private static SegConfig parallax(double deg, double minCam) {
        SegConfig d = SegConfig.DEFAULT;
        return new SegConfig(d.minTextureVar(), d.maxMatchMad(), d.candMinVar(), d.camSlackMad(), d.camSlackRel(),
                d.camWindow(), d.resPx(), d.consPx(), d.consMinMag(), d.consAngleDeg(), d.minRegionBlocks(),
                d.minRegionFrac(), d.maxSpreadPx(), d.dilate(), deg, minCam);
    }

    /** Camera vector (-10, 0) everywhere; one 4x4 patch at (5,5) with residual (rx, 0). */
    private static Labels run(int rx, SegConfig c, int camDx) {
        short[] dx = new short[COLS * ROWS];
        short[] dy = new short[COLS * ROWS];
        Arrays.fill(dx, (short) camDx);
        for (int y = 5; y < 9; y++) {
            for (int x = 5; x < 9; x++) {
                dx[y * COLS + x] = (short) (camDx + rx);
            }
        }
        boolean[] all = new boolean[COLS * ROWS];
        Arrays.fill(all, true);
        Model cam = new Model(Kind.TRANSLATION, GlobalMotion.translation(camDx, 0), 0, 0, 0);
        return Segmenter.group(new MotionField(COLS, ROWS, dx, dy), all, cam, c);
    }

    @Test
    void offByDefault() {
        assertEquals(0.0, SegConfig.DEFAULT.parallaxDeg());
        assertNotEquals(0, run(-7, SegConfig.DEFAULT, -10).get(6, 6));
    }

    @Test
    void regionSlidingFurtherWithTheCameraIsDroppedWhenOn() {
        // Near fence under a camera flow of -10: residual -7, same direction.
        assertEquals(0, run(-7, parallax(30, 4), -10).get(6, 6));
    }

    @Test
    void regionMovingAgainstTheFlowIsKept() {
        // Tracked person: nearly still in the image, residual +10 against the flow.
        assertNotEquals(0, run(10, parallax(30, 4), -10).get(6, 6));
    }

    @Test
    void slowCameraDisablesTheFilter() {
        // Camera flow of 3 px is below parallaxMinCam = 4: nothing is dropped.
        assertNotEquals(0, run(-7, parallax(30, 4), -3).get(6, 6));
    }
}
