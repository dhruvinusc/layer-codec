package codec.decoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import codec.common.CmpFormat.Header;
import codec.common.Display;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Player logic on a fake clock: time only moves when the fake screen waits or the fake source
 * "decodes", so every run is exact and repeatable, with no window and no real sleeping.
 */
class PlayerTest {

    private static final Header H30 = new Header(0, 4, 2, 16, 16, 30, 1, 0, 2, 5, 0);
    private static final long PERIOD = 1_000_000_000L / 30;

    /** Fake world: a clock, a source with a fixed decode cost, a screen with scripted keys. */
    private static final class World implements Player.Source, Player.Screen {
        long now = 1_000_000L; // never 0, which the player uses for "not shown"
        final long decodeNanos;
        long firstDecodeExtra; // a slow cold start (window, JIT, first paint)
        int gets;
        final List<Integer> painted = new ArrayList<>();
        final List<String> statuses = new ArrayList<>();
        final TreeMap<Long, Display.Key> keys = new TreeMap<>();
        final ByteArrayOutputStream log = new ByteArrayOutputStream();

        World(long decodeNanos) {
            this.decodeNanos = decodeNanos;
        }

        @Override
        public FrameCache.Entry get(int k) {
            now += decodeNanos + (gets++ == 0 ? firstDecodeExtra : 0);
            return new FrameCache.Entry(k, 4, 2, new int[8], new byte[4]);
        }

        @Override
        public void show(FrameCache.Entry e, String status) {
            painted.add(e.index());
            statuses.add(status);
        }

        /** Returns a scripted key whose time has come (moving the clock up to it), else waits. */
        @Override
        public Display.Key waitKey(long nanos) {
            Long at = keys.ceilingKey(Long.MIN_VALUE);
            if (at != null && at <= now + nanos) {
                now = Math.max(now, at);
                return keys.remove(at);
            }
            now += nanos;
            if (nanos == Player.PAUSED_POLL_NANOS && keys.isEmpty()) {
                return Display.Key.QUIT; // paused with nothing left to do: end the test run
            }
            return null;
        }

        Player player(int frames) {
            return new Player(H30, frames, this, this, () -> now,
                    new PrintStream(log, true, StandardCharsets.UTF_8), "test");
        }

        String log() {
            return log.toString(StandardCharsets.UTF_8);
        }
    }

    @Test
    void fastDecodePlaysEveryFrameOnTimeAndPausesOnTheLast() throws Exception {
        World w = new World(2_000_000L); // 2 ms per frame, well inside 33 ms
        Player.Report r = w.player(90).run();
        assertEquals(0, r.dropped());
        assertEquals(0, r.late());
        assertFalse(r.interrupted());
        assertEquals(3.0, r.expectedSeconds(), 1e-9);
        assertEquals(3.0, r.measuredSeconds(), 0.004, "within 1/8 of a frame of the clip length");
        int warm = 1 + Player.WARM_PAINTS; // frame 0 shown, then repainted during pre-roll
        for (int k = 0; k < warm; k++) {
            assertEquals(0, w.painted.get(k), "pre-roll paints only frame 0");
        }
        for (int k = 1; k < 90; k++) {
            assertEquals(k, w.painted.get(warm + k - 1), "frames in order, none skipped");
        }
        assertTrue(w.statuses.get(w.statuses.size() - 1).contains("PAUSED"), "pauses on the last frame");
        assertTrue(w.log().contains("frames 90  dropped 0"), w.log());
    }

    @Test
    void slowDecodeDropsFramesButKeepsTheOriginalRate() throws Exception {
        World w = new World(50_000_000L); // 50 ms per frame: only about 20 fps can be shown
        Player.Report r = w.player(120).run();
        assertTrue(r.dropped() > 20, "should drop about a third, dropped " + r.dropped());
        assertEquals(120, r.first().shown() + r.first().dropped() + r.last().shown() + r.last().dropped()
                - overlap(r), "every frame is either shown or dropped");
        assertEquals(119, (int) w.painted.get(w.painted.size() - 1), "the last frame is always shown");
        // Holding the rate means the clip still takes its true length, give or take the last
        // frame's lateness: at most one period (33 ms) plus one decode (50 ms).
        assertEquals(4.0, r.measuredSeconds(), 0.084);
        for (int i = 1; i < w.painted.size(); i++) {
            assertTrue(w.painted.get(i) >= w.painted.get(i - 1), "never goes backwards while playing (the end repaints the last frame as PAUSED)");
        }
    }

    /** Frames counted in both windows (they overlap when the clip is shorter than 200 frames). */
    private static int overlap(Player.Report r) {
        int from = r.last().from();
        int to = r.first().to();
        return Math.max(0, to - from + 1);
    }

    @Test
    void pauseStepAndStepBackMoveOneFrameAndStopTheClock() throws Exception {
        World w = new World(1_000_000L);
        long start = w.now;
        w.keys.put(start + 10 * PERIOD + PERIOD / 2, Display.Key.TOGGLE_PLAY); // pause around frame 10
        w.keys.put(start + 20 * PERIOD, Display.Key.STEP_FORWARD);
        w.keys.put(start + 21 * PERIOD, Display.Key.STEP_FORWARD);
        w.keys.put(start + 22 * PERIOD, Display.Key.STEP_BACK);
        w.keys.put(start + 23 * PERIOD, Display.Key.QUIT);
        Player.Report r = w.player(60).run();
        int pausedAt = w.painted.get(w.painted.size() - 4); // refresh after pause
        List<Integer> tail = w.painted.subList(w.painted.size() - 3, w.painted.size());
        assertEquals(List.of(pausedAt + 1, pausedAt + 2, pausedAt + 1), tail);
        assertTrue(pausedAt >= 9 && pausedAt <= 11, "paused near frame 10, got " + pausedAt);
        assertTrue(r.interrupted(), "timing is flagged as not measured after a pause");
        assertTrue(w.log().contains("not measured"), w.log());
        assertFalse(w.log().contains("NaN"), "no meaningless window numbers after a pause: " + w.log());
        assertFalse(w.log().contains("first    :"), w.log());
    }

    @Test
    void resumeReanchorsTheClockSoNothingIsDroppedAfterAPause() throws Exception {
        World w = new World(1_000_000L);
        long start = w.now;
        w.keys.put(start + 5 * PERIOD + PERIOD / 2, Display.Key.TOGGLE_PLAY); // pause
        w.keys.put(start + 300 * PERIOD, Display.Key.TOGGLE_PLAY); // resume 10 s later
        Player.Report r = w.player(30).run();
        assertEquals(0, r.dropped(), "a long pause must not count as falling behind");
        assertEquals(29, (int) w.painted.get(w.painted.size() - 1));
    }

    @Test
    void statusLineShowsFrameTimeLayersAndCounters() throws Exception {
        World w = new World(1_000_000L);
        w.player(3).run();
        String s = w.statuses.get(0);
        assertTrue(s.startsWith("frame 1/3  0.00 s  n1=2 n2=5  dropped 0  late 0  PLAYING"), s);
    }

    @Test
    void slowStartIsAbsorbedByPrerollNotCountedAsDrops() throws Exception {
        // Mac run 1 (P3.3): about 0.6 s of start-up was counted as falling behind, dropping ~19
        // frames and making a 4.00 s clip "play" in 3.33 s. Pre-roll must remove that.
        World w = new World(2_000_000L);
        w.firstDecodeExtra = 600_000_000L;
        Player.Report r = w.player(120).run();
        assertEquals(0, r.dropped());
        assertEquals(0, r.late());
        assertEquals(4.0, r.measuredSeconds(), 0.004);
    }

    @Test
    void traceHasOneRowPerFrameWithWaitsAndFlags() throws Exception {
        World w = new World(50_000_000L);
        Player p = w.player(30);
        p.run();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        p.writeTrace(new PrintStream(b, true, StandardCharsets.UTF_8));
        String[] lines = b.toString(StandardCharsets.UTF_8).split("\\R");
        assertEquals("frame,due_ms,shown_ms,wait_ms,dropped,late", lines[0]);
        assertEquals(31, lines.length);
        assertTrue(lines[0 + 1].startsWith("0,0.00,"), lines[1]);
        long droppedRows = java.util.Arrays.stream(lines).skip(1).filter(l -> l.endsWith(",1,0")).count();
        assertTrue(droppedRows > 0, "slow decode must show dropped rows");
    }

    @Test
    void prerollWarmsThePaintPathBeforeTheClockStarts() throws Exception {
        World w = new World(1_000_000L);
        Player.Report r = w.player(40).run();
        long zeros = w.painted.stream().filter(k -> k == 0).count();
        assertEquals(1 + Player.WARM_PAINTS, zeros, "frame 0 painted once plus the warm-up repaints");
        assertEquals(0, r.dropped(), "warm-up time is before the clock, so it causes no drops");
        assertEquals(40.0 / 30, r.measuredSeconds(), 0.004);
    }

    @Test
    void quitDuringPrerollEndsCleanly() throws Exception {
        World w = new World(1_000_000L);
        w.keys.put(w.now, Display.Key.QUIT);
        Player.Report r = w.player(40).run();
        assertTrue(r.interrupted() || Double.isNaN(r.measuredSeconds()), "nothing measured");
        assertTrue(w.painted.size() <= 2, "stopped during pre-roll");
    }

    @Test
    void replayAfterTheEndDoesNotSpoilTheTrace() throws Exception {
        // Nov 14: space at the end replays and re-anchors the clock; the trace must still describe
        // the first pass (deadlines from 0, paint times near their deadlines, first-pass flags).
        World w = new World(50_000_000L); // slow: the first pass drops and is late
        long start = w.now;
        w.keys.put(start + 5_000_000_000L, Display.Key.TOGGLE_PLAY); // after the end: replay
        w.keys.put(start + 5_500_000_000L, Display.Key.QUIT);
        Player p = w.player(60);
        Player.Report first = p.report(); // before run: nothing yet
        assertEquals(0, first.dropped());
        p.run();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        p.writeTrace(new PrintStream(b, true, StandardCharsets.UTF_8));
        String[] lines = b.toString(StandardCharsets.UTF_8).split("\\R");
        int droppedRows = 0;
        for (int k = 1; k < lines.length; k++) {
            String[] f = lines[k].split(",", -1);
            if (!f[2].isEmpty()) {
                double lateBy = Double.parseDouble(f[2]) - Double.parseDouble(f[1]);
                assertTrue(lateBy > -1 && lateBy < 200, "frame " + f[0] + " painted " + lateBy + " ms from its deadline");
            }
            droppedRows += f[4].equals("1") ? 1 : 0;
        }
        assertTrue(droppedRows > 0, "the slow first pass dropped frames");
        assertTrue(w.log().contains("dropped"), w.log());
    }
}
