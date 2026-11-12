package codec.decoder;

import codec.common.CmpFormat;
import codec.common.CmpFormat.Header;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Frame-parallel decode-ahead (03 D8, H3 P3.1).
 *
 * <p>Why: one 1080p frame takes 27 to 46 ms on 8 cores and about 78 ms on one core at n = 0, but
 * 30 fps allows 33.3 ms. So several workers each decode a WHOLE frame with the serial
 * {@link DecodeCore#decode} (never the row-parallel version, so W workers use W cores and
 * nothing is oversubscribed). Throughput is about W frames per single-frame time.
 *
 * <p>How order is kept: results go into a map keyed by frame index. {@link #get(int)} waits for
 * exactly that key, so a frame that finishes early simply waits in its slot.
 *
 * <p>How memory is bounded: the consumer's request k defines a window [k, k + capacity). Workers
 * only take frame indices inside the window, so at most {@code capacity} frames are buffered,
 * plus at most one in flight per worker.
 *
 * <p>Seeking: asking for a frame outside the window (a step back, or a jump ahead) starts a new
 * window there. Work from the old window is discarded by a generation counter.
 */
public final class DecodePipeline implements AutoCloseable {

    /** Decodes frame k with the caller's scratch. Production reads the .cmp; tests may add delays. */
    @FunctionalInterface
    interface FrameSource {
        DecodeCore.Decoded decode(int k, DecodeCore.Scratch scratch) throws IOException;
    }

    /** Bytes the window may use: a quarter of the heap, the rest is for the cache and display. */
    private static final int HEAP_SHARE = 4;
    /** About 8 frames at 1080p (H3); grows only so every worker can have something to do. */
    private static final int MAX_CAPACITY = 8;
    private static final int MIN_CAPACITY = 2;

    private final Header header;
    private final int frameCount;
    private final int capacity;
    private final FrameSource source;
    private final List<Thread> threads = new ArrayList<>();

    // All fields below are guarded by "this".
    private final Map<Integer, DecodeCore.Decoded> ready = new HashMap<>();
    private final Map<Integer, IOException> failed = new HashMap<>();
    private int base;
    private int next;
    private int generation;
    private boolean closed;
    private int peakBuffered;
    private long decodedCount;

    /** Production pipeline over an open reader, default worker count and capacity. */
    public DecodePipeline(CmpFormat.Reader reader) {
        this(reader, defaultWorkers(), defaultCapacity(reader.header(), defaultWorkers()));
    }

    /** Production pipeline with explicit sizes (bench and tests). */
    public DecodePipeline(CmpFormat.Reader reader, int workers, int capacity) {
        this(reader.header(), reader.frameCount(), workers, capacity,
                (k, s) -> DecodeCore.decode(reader.header(), reader.payload(k), s));
    }

    DecodePipeline(Header header, int frameCount, int workers, int capacity, FrameSource source) {
        if (workers < 1 || capacity < 1 || frameCount < 0) {
            throw new IllegalArgumentException("workers " + workers + ", capacity " + capacity
                    + ", frames " + frameCount);
        }
        this.header = header;
        this.frameCount = frameCount;
        this.capacity = capacity;
        this.source = source;
        for (int i = 0; i < workers; i++) {
            Thread t = new Thread(this::workerLoop, "decode-" + i);
            t.setDaemon(true);
            threads.add(t);
        }
        for (Thread t : threads) {
            t.start();
        }
    }

    /** Cores minus 2 (display thread and the rest of the system), at least 2 (H3). */
    public static int defaultWorkers() {
        return Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
    }

    /** Frames that fit in a quarter of the heap, between 2 and max(8, workers). */
    public static int defaultCapacity(Header h, int workers) {
        long frameBytes = 3L * Integer.BYTES * h.width() * h.height() + h.blockCount();
        long fit = Runtime.getRuntime().maxMemory() / HEAP_SHARE / frameBytes;
        long cap = Math.min(fit, Math.max(MAX_CAPACITY, workers));
        return (int) Math.max(MIN_CAPACITY, cap);
    }

    public Header header() {
        return header;
    }

    public int frameCount() {
        return frameCount;
    }

    public int capacity() {
        return capacity;
    }

    public int workers() {
        return threads.size();
    }

    /** Largest number of frames ever buffered at once (bounded-memory evidence). */
    public synchronized int peakBuffered() {
        return peakBuffered;
    }

    /** Frames decoded so far, including discarded ones (shows how much a seek wasted). */
    public synchronized long decodedCount() {
        return decodedCount;
    }

    /** True if frame k is already decoded and waiting, so {@link #get} would not block. */
    public synchronized boolean isReady(int k) {
        return ready.containsKey(k);
    }

    /**
     * Returns exactly frame k, waiting for it if needed. Moving forward slides the window and frees
     * older frames; asking outside the window restarts decoding at k.
     */
    public synchronized DecodeCore.Decoded get(int k) throws IOException, InterruptedException {
        if (k < 0 || k >= frameCount) {
            throw new IndexOutOfBoundsException("frame " + k + " out of range 0.." + (frameCount - 1));
        }
        ensureOpen();
        if (k < base || k >= base + capacity) {
            restartAt(k);
        } else if (k > base) {
            slideTo(k);
        }
        while (!ready.containsKey(k)) {
            IOException e = failed.get(k);
            if (e != null) {
                throw new IOException("frame " + k + " failed to decode: " + e.getMessage(), e);
            }
            ensureOpen();
            wait();
        }
        return ready.get(k);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("pipeline is closed");
        }
    }

    private void restartAt(int k) {
        generation++;
        ready.clear();
        failed.clear();
        base = k;
        next = k;
        notifyAll();
    }

    private void slideTo(int k) {
        base = k;
        next = Math.max(next, k);
        dropBelow(ready.entrySet().iterator(), k);
        dropBelow(failed.entrySet().iterator(), k);
        notifyAll();
    }

    private static <V> void dropBelow(Iterator<Map.Entry<Integer, V>> it, int k) {
        while (it.hasNext()) {
            if (it.next().getKey() < k) {
                it.remove();
            }
        }
    }

    private void workerLoop() {
        DecodeCore.Scratch scratch = new DecodeCore.Scratch(header);
        while (true) {
            int k;
            int gen;
            synchronized (this) {
                while (!closed && (next >= frameCount || next >= base + capacity)) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (closed) {
                    return;
                }
                k = next++;
                gen = generation;
            }
            DecodeCore.Decoded result = null;
            IOException error = null;
            try {
                result = source.decode(k, scratch);
            } catch (IOException e) {
                error = e;
            } catch (RuntimeException e) {
                error = new IOException(e.toString(), e);
            }
            publish(k, gen, result, error);
        }
    }

    private synchronized void publish(int k, int gen, DecodeCore.Decoded result, IOException error) {
        decodedCount++;
        if (gen != generation || k < base || closed) {
            return; // stale work from before a seek, or a frame the consumer already passed
        }
        if (error != null) {
            failed.put(k, error);
        } else {
            ready.put(k, result);
            peakBuffered = Math.max(peakBuffered, ready.size());
        }
        notifyAll();
    }

    /** Stops all workers and waits for them; safe to call twice. */
    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            ready.clear();
            notifyAll();
        }
        for (Thread t : threads) {
            boolean interrupted = false;
            while (t.isAlive()) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Worker threads still running; 0 after close (test hook). */
    int liveThreads() {
        int n = 0;
        for (Thread t : threads) {
            if (t.isAlive()) {
                n++;
            }
        }
        return n;
    }
}
