package codec.decoder;

import codec.common.CmpFormat;
import codec.common.CmpFormat.Header;
import codec.common.McCodec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Decode-ahead for motion-compensated files (.cmp mode 2, P4-A).
 *
 * <p>Why not {@link DecodePipeline}: a P frame needs the previous decoded frame, so frames can no
 * longer be decoded independently. But each group of {@link McCodec#GOP} frames starts with an
 * I frame and needs nothing before it. So each worker claims a whole GROUP, decodes its frames
 * in order with its own reference, and publishes them; several groups are decoded at once.
 *
 * <p>Order and memory work as in DecodePipeline: frames are keyed by index and the consumer's
 * request k sets a window [k, k + capacity). A worker waits before publishing a frame beyond the
 * window, and claims a group only if it starts inside the window. Capacity is workers x GOP so
 * every worker has a group to work on (32 frames, about 800 MB of planes at 1080p, inside half the
 * heap). A request outside the window restarts decoding at the group containing k; frames of that
 * group before k are decoded (they are needed) but not stored: at most GOP - 1 extra decodes.
 */
public final class GopPipeline implements FramePipeline {

    @FunctionalInterface
    interface PayloadSource {
        byte[] payload(int k) throws IOException;
    }

    private static final int HEAP_SHARE = 2;

    private final Header header;
    private final int frameCount;
    private final int capacity;
    private final PayloadSource source;
    private final List<Thread> threads = new ArrayList<>();

    // Guarded by "this".
    private final Map<Integer, DecodeCore.Decoded> ready = new HashMap<>();
    private final Map<Integer, IOException> failed = new HashMap<>();
    private int base;
    private int nextGroup;
    private int generation;
    private boolean closed;
    private int peakBuffered;
    private long decodedCount;

    public GopPipeline(CmpFormat.Reader reader, int workers, int capacity) {
        this(reader.header(), reader.frameCount(), workers, capacity, reader::payload);
    }

    GopPipeline(Header header, int frameCount, int workers, int capacity, PayloadSource source) {
        if (header.mode() != CmpFormat.MODE_MC) {
            throw new IllegalArgumentException("GopPipeline is for mode 2 files, got mode " + header.mode());
        }
        if (workers < 1 || capacity < McCodec.GOP) {
            throw new IllegalArgumentException("workers " + workers + ", capacity " + capacity + " (min " + McCodec.GOP + ")");
        }
        this.header = header;
        this.frameCount = frameCount;
        this.capacity = capacity;
        this.source = source;
        for (int i = 0; i < workers; i++) {
            Thread t = new Thread(this::workerLoop, "gop-decode-" + i);
            t.setDaemon(true);
            threads.add(t);
        }
        for (Thread t : threads) {
            t.start();
        }
    }

    /** workers x GOP frames, but no more than half the heap and never below one group. */
    public static int defaultCapacity(Header h, int workers) {
        long frameBytes = 3L * Integer.BYTES * h.width() * h.height() + h.blockCount();
        long fit = Runtime.getRuntime().maxMemory() / HEAP_SHARE / frameBytes;
        return (int) Math.max(McCodec.GOP, Math.min((long) workers * McCodec.GOP, fit));
    }

    @Override
    public Header header() {
        return header;
    }

    @Override
    public int frameCount() {
        return frameCount;
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public int workers() {
        return threads.size();
    }

    @Override
    public synchronized int peakBuffered() {
        return peakBuffered;
    }

    @Override
    public synchronized long decodedCount() {
        return decodedCount;
    }

    @Override
    public synchronized boolean isReady(int k) {
        return ready.containsKey(k);
    }

    @Override
    public synchronized DecodeCore.Decoded get(int k) throws IOException, InterruptedException {
        if (k < 0 || k >= frameCount) {
            throw new IndexOutOfBoundsException("frame " + k + " out of range 0.." + (frameCount - 1));
        }
        ensureOpen();
        if (k < base || k >= base + capacity) {
            generation++;
            ready.clear();
            failed.clear();
            base = k;
            nextGroup = k / McCodec.GOP;
            notifyAll();
        } else if (k > base) {
            base = k;
            nextGroup = Math.max(nextGroup, k / McCodec.GOP);
            dropBelow(ready.entrySet().iterator(), k);
            dropBelow(failed.entrySet().iterator(), k);
            notifyAll();
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

    private static <V> void dropBelow(Iterator<Map.Entry<Integer, V>> it, int k) {
        while (it.hasNext()) {
            if (it.next().getKey() < k) {
                it.remove();
            }
        }
    }

    private void workerLoop() {
        McCodec.State st = new McCodec.State(header.paddedW(), header.paddedH());
        byte[] types = new byte[header.blockCount()];
        short[] coef = new short[header.blockCount() * CmpFormat.COEF_PER_BLOCK];
        while (true) {
            int group;
            int gen;
            synchronized (this) {
                while (!closed && (nextGroup * McCodec.GOP >= frameCount || nextGroup * McCodec.GOP >= base + capacity)) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (closed) {
                    return;
                }
                group = nextGroup++;
                gen = generation;
            }
            decodeGroup(group, gen, st, types, coef);
        }
    }

    /** Decodes one group in order; stops early if a seek or close makes it stale. */
    private void decodeGroup(int group, int gen, McCodec.State st, byte[] types, short[] coef) {
        int first = group * McCodec.GOP;
        int end = Math.min(frameCount, first + McCodec.GOP);
        for (int f = first; f < end; f++) {
            DecodeCore.Decoded d;
            try {
                McCodec.decode(header, st, source.payload(f), f, types, coef);
                d = new DecodeCore.Decoded(st.frame(header.width(), header.height()), types.clone());
            } catch (IOException | RuntimeException e) {
                IOException err = e instanceof IOException io ? io : new IOException(e.toString(), e);
                failRest(f, end, gen, err); // later frames of the group cannot be decoded either
                return;
            }
            if (!publish(f, gen, d)) {
                return;
            }
        }
    }

    /** Waits until frame f fits the window; returns false if the group became stale. */
    private synchronized boolean publish(int f, int gen, DecodeCore.Decoded d) {
        decodedCount++;
        while (!closed && gen == generation && f >= base + capacity) {
            try {
                wait();
            } catch (InterruptedException e) {
                return false;
            }
        }
        if (closed || gen != generation) {
            return false;
        }
        if (f >= base) {
            ready.put(f, d);
            peakBuffered = Math.max(peakBuffered, ready.size());
            notifyAll();
        }
        return true; // frames before base are still needed as references, just not stored
    }

    private synchronized void failRest(int from, int end, int gen, IOException e) {
        if (closed || gen != generation) {
            return;
        }
        for (int f = from; f < end; f++) {
            if (f >= base) {
                failed.put(f, e);
            }
        }
        notifyAll();
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            ready.clear();
            notifyAll();
        }
        boolean interrupted = false;
        for (Thread t : threads) {
            while (t.isAlive()) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
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
