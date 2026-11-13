package codec.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import codec.common.Types.Frame;
import codec.common.Types.Labels;
import codec.common.Types.MotionField;
import org.junit.jupiter.api.Test;

class VizSegTest {

    @Test
    void defaultFramesAre20_50_80Percent() {
        assertArrayEquals(new int[] {87, 218, 349}, VizSeg.defaultFrames(437));
    }

    @Test
    void backgroundTintIsGreenAndForegroundRed() {
        Frame f = Frame.allocate(32, 16);
        java.util.Arrays.fill(f.r(), 128);
        java.util.Arrays.fill(f.g(), 128);
        java.util.Arrays.fill(f.b(), 128);
        Labels l = new Labels(2, 1, new byte[] {0, 1});
        int[] rgb = VizSeg.render(f, l, null);
        int bg = rgb[0];
        int fg = rgb[16];
        assertEquals(true, ((bg >> 8) & 0xFF) > ((bg >> 16) & 0xFF), "background greener than red");
        assertEquals(true, ((fg >> 16) & 0xFF) > ((fg >> 8) & 0xFF), "foreground redder than green");
    }

    @Test
    void arrowPointsAlongContentMotion() {
        Frame f = Frame.allocate(32, 32);
        Labels l = new Labels(2, 2, new byte[4]);
        // dx = -6 means the block came from 6 px to the left, so content moved RIGHT: arrow goes +x.
        MotionField mv = new MotionField(2, 2, new short[] {-6, 0, 0, 0}, new short[4]);
        int[] rgb = VizSeg.render(f, l, mv);
        assertEquals(VizSeg.ARROW_COLOR, rgb[8 * 32 + 14]);
        assertEquals(true, rgb[8 * 32 + 2] != VizSeg.ARROW_COLOR, "nothing drawn to the left");
    }
}
