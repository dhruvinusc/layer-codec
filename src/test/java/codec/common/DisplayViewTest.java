package codec.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The display's view logic (fit, side by side, 1:1 zoom with pan, overlay) without a screen. */
class DisplayViewTest {

    /** Pixel value = its own index + base, so any misplaced pixel shows up. */
    private static int[] ramp(int w, int h, int base) {
        int[] a = new int[w * h];
        for (int i = 0; i < a.length; i++) {
            a[i] = base + i;
        }
        return a;
    }

    @Test
    void fitFillsTheWindowWhenSizesMatch() {
        Display.View v = new Display.View();
        int[] src = ramp(4, 2, 1);
        int[] out = new int[8];
        v.render(src, null, 4, 2, out, 4, 2);
        assertArrayEquals(src, out);
    }

    @Test
    void fitCentersWithBlackBarsWhenTheWindowIsWider() {
        Display.View v = new Display.View();
        int[] out = new int[6 * 2];
        v.render(ramp(2, 2, 1), null, 2, 2, out, 6, 2);
        assertArrayEquals(new int[] {0, 0, 1, 2, 0, 0, 0, 0, 3, 4, 0, 0}, out);
    }

    @Test
    void sideBySidePutsDecodedLeftAndOriginalRight() {
        Display.View v = new Display.View();
        v.toggle(Display.View.Toggle.SIDE_BY_SIDE);
        int[] left = ramp(4, 2, 100);
        int[] right = ramp(4, 2, 200);
        int[] out = new int[8 * 2];
        v.render(left, right, 4, 2, out, 8, 2);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 4; x++) {
                assertEquals(left[y * 4 + x], out[y * 8 + x]);
                assertEquals(right[y * 4 + x], out[y * 8 + 4 + x]);
            }
        }
    }

    @Test
    void sideBySideWithoutAnOriginalJustFits() {
        Display.View v = new Display.View();
        v.toggle(Display.View.Toggle.SIDE_BY_SIDE);
        int[] out = new int[6 * 2];
        v.render(ramp(2, 2, 1), null, 2, 2, out, 6, 2);
        assertArrayEquals(new int[] {0, 0, 1, 2, 0, 0, 0, 0, 3, 4, 0, 0}, out);
        assertEquals(" | side by side needs the original .mp4", v.tags(false),
                "asking for side by side without an original says why nothing split");
    }

    @Test
    void zoomCopiesPixelsOneToOneFromThePanOffsetAndClamps() {
        Display.View v = new Display.View();
        v.toggle(Display.View.Toggle.ZOOM);
        int[] src = ramp(10, 6, 0);
        int[] out = new int[4 * 3];
        v.panX = 3;
        v.panY = 2;
        v.render(src, null, 10, 6, out, 4, 3);
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 4; x++) {
                assertEquals(src[(2 + y) * 10 + 3 + x], out[y * 4 + x], "no scaling at 1:1");
            }
        }
        v.panX = 100;
        v.panY = -5;
        v.render(src, null, 10, 6, out, 4, 3);
        assertEquals(6, v.panX, "pan stops at the right edge");
        assertEquals(0, v.panY, "pan stops at the top edge");
        assertEquals(src[6], out[0]);
    }

    @Test
    void zoomOnASmallFrameCentersItWithoutScaling() {
        Display.View v = new Display.View();
        v.toggle(Display.View.Toggle.ZOOM);
        int[] out = new int[6 * 2];
        v.render(ramp(2, 2, 1), null, 2, 2, out, 6, 2);
        assertArrayEquals(new int[] {0, 0, 1, 2, 0, 0, 0, 0, 3, 4, 0, 0}, out);
        assertEquals(0, v.panX);
    }

    @Test
    void zoomedSideBySideShowsTheSameRegionInBothHalves() {
        Display.View v = new Display.View();
        v.toggle(Display.View.Toggle.SIDE_BY_SIDE);
        v.toggle(Display.View.Toggle.ZOOM);
        int[] left = ramp(10, 6, 1000);
        int[] right = ramp(10, 6, 5000);
        int[] out = new int[8 * 3];
        v.panX = 2;
        v.panY = 1;
        v.render(left, right, 10, 6, out, 8, 3);
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 4; x++) {
                int src = (1 + y) * 10 + 2 + x;
                assertEquals(left[src], out[y * 8 + x]);
                assertEquals(right[src], out[y * 8 + 4 + x], "same spot in the original");
            }
        }
    }

    @Test
    void overlayTintsOnlyForegroundBlocks() {
        int w = 16;
        int h = 8;
        int[] gray = new int[w * h];
        java.util.Arrays.fill(gray, (128 << 16) | (128 << 8) | 128);
        int[] out = new int[w * h];
        Display.View.tint(gray, new byte[] {1, 0}, 2, w, h, out);
        int red = (128 * 5 + 255 * 3) >> 3;
        int dim = (128 * 5) >> 3;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int want = x < 8 ? (red << 16) | (dim << 8) | dim : gray[0];
                assertEquals(want, out[y * w + x], "pixel " + x + "," + y);
            }
        }
    }

    @Test
    void overlayFindsTheRightBlockRowOnPaddedFrames() {
        // 1080-style crop: true height 10 inside a padded grid of 2 block rows, 1 block per row.
        int[] src = new int[8 * 10];
        int[] out = new int[8 * 10];
        Display.View.tint(src, new byte[] {0, 1}, 1, 8, 10, out);
        assertEquals(0, out[7 * 8], "row 7 is in block row 0 (background)");
        assertTrue(out[8 * 8] != 0, "row 8 is in block row 1 (foreground)");
    }

    @Test
    void statusTagsNameTheActiveViews() {
        Display.View v = new Display.View();
        v.toggle(Display.View.Toggle.SIDE_BY_SIDE);
        v.toggle(Display.View.Toggle.ZOOM);
        v.toggle(Display.View.Toggle.OVERLAY);
        assertEquals(" | side by side | 1:1 (drag to pan) | foreground in red", v.tags(true));
        v.toggle(Display.View.Toggle.ZOOM);
        assertEquals(" | side by side | foreground in red", v.tags(true));
    }

    @Test
    void everyViewKeySaysWhatItDid() {
        Display.View v = new Display.View();
        v.toggle(Display.View.Toggle.SIDE_BY_SIDE);
        assertEquals("side by side ON", v.describe(Display.View.Toggle.SIDE_BY_SIDE, true));
        assertTrue(v.describe(Display.View.Toggle.SIDE_BY_SIDE, false).contains("no original"));
        v.toggle(Display.View.Toggle.SIDE_BY_SIDE);
        assertEquals("side by side OFF", v.describe(Display.View.Toggle.SIDE_BY_SIDE, false));
        v.toggle(Display.View.Toggle.ZOOM);
        assertEquals("1:1 zoom ON (drag to pan)", v.describe(Display.View.Toggle.ZOOM, true));
        assertEquals("foreground overlay OFF", v.describe(Display.View.Toggle.OVERLAY, true));
    }
}
