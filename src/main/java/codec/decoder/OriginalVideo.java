package codec.decoder;

import codec.common.Types;
import codec.common.VideoIO;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The original mp4's frames for side by side (P3.4), read in step with the decoded frames.
 *
 * <p>The mp4 can only be read forward (an FFmpeg pipe, through {@link VideoIO}), so frames are
 * read as the player moves ahead, and the last {@link #RING} are kept packed (0xRRGGBB) for
 * stepping back. A step back past the ring reopens the clip and reads forward again from
 * frame 0: slow for frames near the end of SAL (a few seconds), but rare. Frames come out
 * upright and at true size, exactly like the decoder's output, so the two halves line up.
 * Not thread-safe: only the player thread uses it.
 */
final class OriginalVideo implements AutoCloseable {
    /** Same depth as the decoded frames' step-back cache (P3.2). */
    static final int RING = FrameCache.DEFAULT_CAPACITY;

    private final Path mp4;
    private final Types.Frame scratch;
    private final int[][] slots = new int[RING][];
    private final Map<Integer, Integer> slotOf = new HashMap<>();
    private VideoIO.Reader reader;
    private int next;
    private boolean ended;
    private long inserted;
    private int reopens;

    OriginalVideo(Path mp4, int width, int height) throws IOException {
        Types.VideoInfo info = VideoIO.probe(mp4);
        if (info.width() != width || info.height() != height) {
            throw new IOException("original " + mp4.getFileName() + " is " + info.width() + "x"
                    + info.height() + " upright, but the .cmp is " + width + "x" + height
                    + ": not the same video");
        }
        this.mp4 = mp4;
        this.scratch = Types.Frame.allocate(width, height);
        this.reader = VideoIO.open(mp4);
    }

    /** Frame k, packed, or null if the mp4 ends before frame k. */
    int[] get(int k) throws IOException {
        Integer slot = slotOf.get(k);
        if (slot != null) {
            return slots[slot];
        }
        if (k < next) {
            reopen();
        }
        while (next <= k && !ended) {
            if (!reader.read(scratch)) {
                ended = true;
                break;
            }
            int idx = next++;
            if (idx > k - RING) {
                insert(idx);
            }
        }
        slot = slotOf.get(k);
        return slot == null ? null : slots[slot];
    }

    private void reopen() throws IOException {
        reader.close();
        reader = VideoIO.open(mp4);
        next = 0;
        ended = false;
        reopens++;
    }

    private void insert(int idx) {
        int slot = (int) (inserted++ % RING);
        if (slots[slot] == null) {
            slots[slot] = new int[scratch.width() * scratch.height()];
        } else {
            slotOf.values().remove(slot);
        }
        int[] dst = slots[slot];
        int[] r = scratch.r();
        int[] g = scratch.g();
        int[] b = scratch.b();
        for (int i = 0; i < dst.length; i++) {
            dst[i] = (r[i] << 16) | (g[i] << 8) | b[i];
        }
        slotOf.put(idx, slot);
    }

    /** How often a step back past the ring forced a reread from frame 0 (test hook). */
    int reopens() {
        return reopens;
    }

    @Override
    public void close() {
        reader.close();
    }
}
