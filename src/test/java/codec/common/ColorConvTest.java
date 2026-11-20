package codec.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codec.common.Types.Frame;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ColorConvTest {

    @Test
    void weightsSumTo256() {
        assertEquals(256, ColorConv.WR + ColorConv.WG + ColorConv.WB);
    }

    @Test
    void knownValues() {
        assertEquals(0, ColorConv.luma(0, 0, 0));
        assertEquals(255, ColorConv.luma(255, 255, 255));
        assertEquals(77, ColorConv.luma(255, 0, 0));
        assertEquals(149, ColorConv.luma(0, 255, 0));
        assertEquals(29, ColorConv.luma(0, 0, 255));
    }

    @Test
    void grayIsExactForEveryLevelIncludingAbove127() {
        for (int v = 0; v <= 255; v++) {
            assertEquals(v, ColorConv.luma(v, v, v), "level " + v);
        }
    }

    @Test
    void withinOneLevelOfFloatFormula() {
        Random rnd = new Random(576);
        for (int i = 0; i < 100_000; i++) {
            int r = rnd.nextInt(256);
            int g = rnd.nextInt(256);
            int b = rnd.nextInt(256);
            double exact = 0.299 * r + 0.587 * g + 0.114 * b;
            assertTrue(Math.abs(ColorConv.luma(r, g, b) - exact) <= 1.0, r + "," + g + "," + b);
        }
    }

    @Test
    void planeMatchesScalar() {
        Frame f = new Frame(2, 1, new int[] {200, 10}, new int[] {130, 20}, new int[] {250, 30});
        assertArrayEquals(new int[] {ColorConv.luma(200, 130, 250), ColorConv.luma(10, 20, 30)},
                ColorConv.luma(f));
    }
}
