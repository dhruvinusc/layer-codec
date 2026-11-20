package codec.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codec.common.Types;
import org.junit.jupiter.api.Test;

class PsnrTest {

    @Test
    void identicalFramesHitTheCap() {
        Types.Frame a = Types.Frame.allocate(16, 8);
        Psnr p = new Psnr();
        p.add(a, a, new byte[2], 2);
        assertEquals(Psnr.CAP, p.meanFramePsnr(Psnr.ALL));
    }

    @Test
    void knownErrorGivesKnownPsnr() {
        // Every sample off by 1 -> MSE 1 -> PSNR = 20 log10(255) = 48.13 dB.
        Types.Frame a = Types.Frame.allocate(16, 8);
        Types.Frame b = Types.Frame.allocate(16, 8);
        java.util.Arrays.fill(b.r(), 1);
        java.util.Arrays.fill(b.g(), 1);
        java.util.Arrays.fill(b.b(), 1);
        Psnr p = new Psnr();
        p.add(a, b, new byte[2], 2);
        assertEquals(20 * Math.log10(255), p.meanFramePsnr(Psnr.ALL), 1e-9);
    }

    @Test
    void foregroundAndBackgroundAreSeparated() {
        // Two 8x8 blocks side by side: left fg exact, right bg off by 4.
        Types.Frame a = Types.Frame.allocate(16, 8);
        Types.Frame b = Types.Frame.allocate(16, 8);
        for (int y = 0; y < 8; y++) {
            for (int x = 8; x < 16; x++) {
                b.g()[y * 16 + x] = 4;
            }
        }
        Psnr p = new Psnr();
        p.add(a, b, new byte[] {1, 0}, 2);
        assertEquals(Psnr.CAP, p.lastFrame(Psnr.FG));
        assertEquals(10 * Math.log10(255.0 * 255.0 * 3 / 16), p.lastFrame(Psnr.BG), 1e-9); // MSE = 16/3
        assertTrue(p.lastFrame(Psnr.ALL) > p.lastFrame(Psnr.BG));
    }

    @Test
    void missingLayerIsNaNNotZero() {
        Types.Frame a = Types.Frame.allocate(16, 8);
        Psnr p = new Psnr();
        p.add(a, a, new byte[] {0, 0}, 2);
        assertTrue(Double.isNaN(p.lastFrame(Psnr.FG)));
        assertTrue(Double.isNaN(p.meanFramePsnr(Psnr.FG)));
    }
}
