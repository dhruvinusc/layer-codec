package codec.common;

import java.util.function.LongSupplier;

/**
 * Steady playback clock (03 D8): frame i is due at t0 + (i - i0) * fpsDen / fpsNum seconds.
 *
 * <p>Deadlines come from the frame index, never from "sleep one period after the last frame".
 * A fixed sleep adds decode and paint time to every frame, so the video slowly falls behind;
 * index-based deadlines absorb that jitter. Integer nanoseconds and the exact rational fps mean
 * no rounding drift even over long clips.
 */
public final class Clock {

    private final long fpsNum;
    private final long fpsDen;
    private final LongSupplier now;
    private long t0;
    private int i0;

    public Clock(int fpsNum, int fpsDen) {
        this(fpsNum, fpsDen, System::nanoTime);
    }

    /** Package-private time source for tests. */
    Clock(int fpsNum, int fpsDen, LongSupplier now) {
        if (fpsNum <= 0 || fpsDen <= 0) {
            throw new IllegalArgumentException("fps must be positive");
        }
        this.fpsNum = fpsNum;
        this.fpsDen = fpsDen;
        this.now = now;
        start(0);
    }

    /** Anchors the clock: {@code frameIndex} is due right now. Call again after a pause or step. */
    public void start(int frameIndex) {
        t0 = now.getAsLong();
        i0 = frameIndex;
    }

    public long dueNanos(int frameIndex) {
        return t0 + (frameIndex - i0) * 1_000_000_000L * fpsDen / fpsNum;
    }

    /** Positive: time to wait. Negative: how late the frame already is. */
    public long nanosUntil(int frameIndex) {
        return dueNanos(frameIndex) - now.getAsLong();
    }

    public long periodNanos() {
        return 1_000_000_000L * fpsDen / fpsNum;
    }

    public long nowNanos() {
        return now.getAsLong();
    }
}
