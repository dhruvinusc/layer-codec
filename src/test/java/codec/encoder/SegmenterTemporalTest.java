package codec.encoder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codec.common.Types.Labels;
import codec.common.Types.MotionField;
import codec.encoder.Segmenter.Run;
import codec.encoder.Segmenter.SegConfig;
import codec.encoder.Segmenter.Temporal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SegmenterTemporalTest {

    static final int COLS = 8;
    static final int ROWS = 4;

    private static Labels one(int... blocks) {
        byte[] id = new byte[COLS * ROWS];
        for (int b : blocks) {
            id[b] = 1;
        }
        return new Labels(COLS, ROWS, id);
    }

    private static MotionField still() {
        return new MotionField(COLS, ROWS, new short[COLS * ROWS], new short[COLS * ROWS]);
    }

    private static MotionField uniform(int dx) {
        short[] x = new short[COLS * ROWS];
        java.util.Arrays.fill(x, (short) dx);
        return new MotionField(COLS, ROWS, x, new short[COLS * ROWS]);
    }

    @Test
    void singleFrameBlipIsRemovedAndOneFrameGapIsFilled() {
        Temporal tv = new Temporal();
        assertNull(tv.push(one(9), still()));       // frame 1: fg at 9
        Labels f1 = tv.push(one(), still());         // frame 2: gap at 9; also outputs frame 1
        Labels f2 = tv.push(one(9, 20), still());    // frame 3: 9 back, blip at 20; outputs frame 2
        Labels f3 = tv.flush();                      // frame 3 final
        assertEquals(1, f1.get(1, 1), "frame 1 is the clip start: its own label plus frame 2 (empty) -> 2 of 3 with self-copy");
        assertEquals(1, f2.get(1, 1), "gap filled: fg in frames 1 and 3");
        assertEquals(1, f3.get(4, 2), "last frame keeps its own label (missing neighbour = itself)");
        assertEquals(0, f2.get(4, 2));
    }

    @Test
    void neighboursAreReadThroughMotionNotAtTheSameGridPosition() {
        // Object moves right by one block per frame: content at block x in frame t came from x-1
        // in frame t-1, so the D12 vector is (-16, 0).
        Temporal tv = new Temporal();
        tv.push(one(8 + 1), uniform(-16));
        Labels mid = tv.push(one(8 + 2), uniform(-16));   // final of frame 1
        Labels f2 = tv.push(one(8 + 3), uniform(-16));    // final of frame 2
        assertEquals(1, f2.get(2, 1), "the mover stays foreground in the middle frame");
        assertEquals(0, f2.get(1, 1), "no ghost left where it used to be");
        assertEquals(1, mid.get(1, 1));
    }

    @Test
    void runEmitsOneLabelSetPerFrameFrameZeroCopiesFrameOneAndIsDeterministic() {
        int w = 64;
        int h = 48;
        List<int[]> frames = clip(w, h, 6);
        List<Labels> a = runAll(frames, w, h);
        List<Labels> b = runAll(frames, w, h);
        assertEquals(6, a.size());
        assertArrayEquals(a.get(0).id(), a.get(1).id());
        for (int k = 0; k < a.size(); k++) {
            assertArrayEquals(a.get(k).id(), b.get(k).id());
        }
    }

    @Test
    void runStatsCoverEveryFrame() {
        int w = 64;
        int h = 48;
        Run r = new Run(w, h, SegConfig.DEFAULT, GlobalMotion.GmConfig.DEFAULT, MotionEstimator.HierParams.DEFAULT);
        for (int[] f : clip(w, h, 4)) {
            r.push(f);
        }
        r.finish();
        assertEquals(4, r.stats().size());
        for (int k = 0; k < 4; k++) {
            assertEquals(k, r.stats().get(k).frame());
            assertTrue(r.stats().get(k).fgPct() >= 0);
        }
    }

    private static List<Labels> runAll(List<int[]> frames, int w, int h) {
        Run r = new Run(w, h, SegConfig.DEFAULT, GlobalMotion.GmConfig.DEFAULT, MotionEstimator.HierParams.DEFAULT);
        List<Labels> out = new ArrayList<>();
        for (int[] f : frames) {
            out.addAll(r.push(f));
        }
        out.addAll(r.finish());
        return out;
    }

    /** Textured background panning 2 px per frame with a textured square moving 6 px the other way. */
    private static List<int[]> clip(int w, int h, int n) {
        Random rnd = new Random(3);
        int bw = w + 64;
        int[] bg = new int[bw * h];
        for (int i = 0; i < bg.length; i++) {
            bg[i] = rnd.nextInt(256);
        }
        int[] obj = new int[16 * 16];
        for (int i = 0; i < obj.length; i++) {
            obj[i] = rnd.nextInt(256);
        }
        List<int[]> out = new ArrayList<>();
        for (int t = 0; t < n; t++) {
            int[] f = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    f[y * w + x] = bg[y * bw + x + 2 * t];
                }
            }
            int ox = 8 + 6 * t;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16 && ox + x < w; x++) {
                    f[(16 + y) * w + ox + x] = obj[y * 16 + x];
                }
            }
            out.add(f);
        }
        return out;
    }
}
