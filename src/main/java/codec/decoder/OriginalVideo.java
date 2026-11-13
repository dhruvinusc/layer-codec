package codec.decoder;

import codec.common.Types;
import codec.common.VideoIO;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The original mp4's frames for side by side (P3.4), read AHEAD on a background thread (P3.4c).
 *
 * <p>Why a thread: reading a 1080p frame from FFmpeg and packing it takes several ms. Done on the
 * player thread (first version), playback with the original dropped 13 to 45% of frames on the
 * Mac (Nov 13). Now a reader thread keeps up to {@link #AHEAD} frames ready beyond the frame the
 * player last asked for, and keeps {@link #BEHIND} frames behind it for stepping back, the same
 * idea as the decode pipeline (P3.1) plus the step-back cache (P3.2).
 *
 * <p>The mp4 can only be read forward (an FFmpeg pipe through {@link VideoIO}). A step back past
 * the kept frames restarts the reader from frame 0; it reads forward, keeping only frames near
 * the target. Frames are upright and at true size, exactly like the decoder's output.
 *
 * <p>Memory: at most BEHIND + AHEAD + 1 packed frames (39 x 8.3 MB = about 320 MB at 1080p).
 * A frame the player is showing is never reused while it can still be on screen: buffers are
 * recycled only after they fall more than BEHIND frames behind the latest request.
 */
final class OriginalVideo implements AutoCloseable {
    /** Frames kept behind the latest request: same depth as the decoded step-back cache. */
    static final int BEHIND = FrameCache.DEFAULT_CAPACITY;
    /** Frames read ahead of the latest request: same as the decode window (P3.1). */
    static final int AHEAD = 8;

    private final Path mp4;
    private final int width;
    private final int height;
    private final Thread thread;

    // Guarded by "this".
    private final Map<Integer, int[]> frames = new HashMap<>();
    private final ArrayDeque<int[]> spare = new ArrayDeque<>();
    private int want;
    private int next;
    private boolean ended;
    private boolean closed;
    private boolean restart;
    private IOException error;
    private int reopens;
    private int peakKept;
    private volatile VideoIO.Reader reader;

    OriginalVideo(Path mp4, int width, int height) throws IOException {
        Types.VideoInfo info = VideoIO.probe(mp4);
        if (info.width() != width || info.height() != height) {
            throw new IOException("original " + mp4.getFileName() + " is " + info.width() + "x"
                    + info.height() + " upright, but the .cmp is " + width + "x" + height
                    + ": not the same video");
        }
        this.mp4 = mp4;
        this.width = width;
        this.height = height;
        this.reader = VideoIO.open(mp4);
        this.thread = new Thread(this::readLoop, "original-reader");
        thread.setDaemon(true);
        thread.start();
    }

    /** Frame k, packed 0xRRGGBB, or null if the mp4 ends before frame k. Waits if not read yet. */
    synchronized int[] get(int k) throws IOException, InterruptedException {
        if (closed) {
            throw new IllegalStateException("original reader is closed");
        }
        want = k;
        if (!frames.containsKey(k) && k < next) {
            // Step back past the kept frames: start the mp4 again from frame 0.
            restart = true;
            reopens++;
            frames.clear(); // dropped, not recycled: the screen may still show one of them
            next = 0;
            ended = false;
        }
        evictLocked();
        notifyAll();
        while (!frames.containsKey(k)) {
            if (error != null) {
                throw new IOException("original stopped at frame " + next + ": " + error.getMessage(), error);
            }
            if (ended && next <= k) {
                return null;
            }
            wait();
        }
        return frames.get(k);
    }

    /** True if frame k is already read (test hook for read-ahead). */
    synchronized boolean isReady(int k) {
        return frames.containsKey(k);
    }

    /** Largest number of frames ever kept at once (bounded-memory test hook). */
    synchronized int peakKept() {
        return peakKept;
    }

    /** How often a step back past the kept frames restarted the mp4 (test hook). */
    synchronized int reopens() {
        return reopens;
    }

    private void evictLocked() {
        Iterator<Map.Entry<Integer, int[]>> it = frames.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, int[]> e = it.next();
            if (e.getKey() < want - BEHIND || e.getKey() > want + AHEAD) {
                spare.push(e.getValue());
                it.remove();
            }
        }
    }

    private void readLoop() {
        Types.Frame scratch = Types.Frame.allocate(width, height);
        while (true) {
            boolean reopen;
            synchronized (this) {
                while (!closed && !restart && (ended || next > want + AHEAD || error != null)) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (closed) {
                    return;
                }
                reopen = restart;
                restart = false;
            }
            try {
                if (reopen) {
                    reader.close();
                    reader = VideoIO.open(mp4);
                }
                boolean ok = reader.read(scratch);
                store(ok, scratch);
            } catch (IOException e) {
                synchronized (this) {
                    if (!closed && !restart) {
                        error = e;
                    }
                    notifyAll();
                }
            }
        }
    }

    /** Files the frame just read, unless a restart made it stale. Packs outside the lock. */
    private void store(boolean ok, Types.Frame f) {
        int idx;
        int[] buf;
        synchronized (this) {
            if (restart || closed) {
                return; // read from the old stream; the restart starts over at frame 0
            }
            if (!ok) {
                ended = true;
                notifyAll();
                return;
            }
            idx = next++;
            if (idx < want - BEHIND) {
                return; // catching up after a restart: too far behind the target to keep
            }
            buf = spare.isEmpty() ? new int[width * height] : spare.pop();
        }
        int[] r = f.r();
        int[] g = f.g();
        int[] b = f.b();
        for (int i = 0; i < buf.length; i++) {
            buf[i] = (r[i] << 16) | (g[i] << 8) | b[i];
        }
        synchronized (this) {
            if (restart || closed) {
                return;
            }
            frames.put(idx, buf);
            peakKept = Math.max(peakKept, frames.size());
            notifyAll();
        }
    }

    /** Stops the reader thread and FFmpeg; safe to call twice. */
    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            notifyAll();
        }
        reader.close(); // unblocks a read in progress by ending the FFmpeg process
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Reader thread still running; false after close (test hook). */
    boolean readerAlive() {
        return thread.isAlive();
    }
}
