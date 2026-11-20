package codec.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ClockTest {

    private long fakeNow;

    @Test
    void deadlinesComeFromIndexNotFromLastFrame() {
        fakeNow = 1_000;
        Clock c = new Clock(30, 1, () -> fakeNow);
        assertEquals(1_000, c.dueNanos(0));
        assertEquals(1_000 + 33_333_333, c.dueNanos(1));
        // Frame 300 is exactly 10 s later: no accumulated rounding.
        assertEquals(1_000 + 10_000_000_000L, c.dueNanos(300));
    }

    @Test
    void lateFrameReportsNegativeWait() {
        fakeNow = 0;
        Clock c = new Clock(30, 1, () -> fakeNow);
        fakeNow = 50_000_000; // 50 ms in, frame 1 was due at 33.3 ms
        assertEquals(-16_666_667, c.nanosUntil(1));
    }

    @Test
    void restartAfterPauseReanchors() {
        fakeNow = 0;
        Clock c = new Clock(30, 1, () -> fakeNow);
        fakeNow = 5_000_000_000L; // paused for a while at frame 40
        c.start(40);
        assertEquals(5_000_000_000L, c.dueNanos(40));
        assertEquals(5_000_000_000L + 33_333_333, c.dueNanos(41));
    }

    @Test
    void ntscRationalRate() {
        fakeNow = 0;
        Clock c = new Clock(30000, 1001, () -> fakeNow);
        assertEquals(33_366_666, c.periodNanos());
        assertEquals(1001L * 1_000_000_000L / 30, c.dueNanos(1000)); // 1000 frames = 33.3667 s
    }
}
