package codec.decoder;

import codec.common.CmpFormat.Header;
import codec.common.Types;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Step-back cache over a {@link DecodePipeline} (H3 P3.2).
 *
 * <p>Why: the pipeline only decodes forward from the requested frame, so a step back would
 * restart it and wait for a fresh decode (54 to 72 ms per 1080p frame). Keeping the last
 * {@code capacity} frames the player asked for makes left-arrow instant for those frames.
 *
 * <p>Why packed RGB: one {@code int} per pixel (0xRRGGBB) is a third of the three int planes
 * DecodeCore returns: 8.3 MB instead of 24.9 MB per 1080p frame, so 30 frames cost about
 * 250 MB instead of 750 MB. It is also the layout the display paints.
 *
 * <p>Beyond the ring, a frame is decoded again through the pipeline, which reads it straight
 * from the .cmp via the footer offset table (random access, no rereading from the start).
 *
 * <p>Eviction: oldest INSERTED frame goes first. Buffers are reused, so a returned
 * {@link Entry} stays valid until {@code capacity} more frames have been inserted. Not
 * thread-safe: only the player thread calls it.
 */
public final class FrameCache {

    /** About one second of video at 30 fps (H3). */
    public static final int DEFAULT_CAPACITY = 30;

    /** One shown frame: packed pixels 0xRRGGBB, row-major, true size, plus the 8x8 block types. */
    public record Entry(int index, int width, int height, int[] rgb, byte[] types) {

        /** Unpacks into three planes (tests and tools; the player paints the packed form). */
        public Types.Frame toFrame() {
            Types.Frame f = Types.Frame.allocate(width, height);
            for (int i = 0; i < rgb.length; i++) {
                int p = rgb[i];
                f.r()[i] = (p >>> 16) & 0xFF;
                f.g()[i] = (p >>> 8) & 0xFF;
                f.b()[i] = p & 0xFF;
            }
            return f;
        }
    }

    private final DecodePipeline pipeline;
    private final int capacity;
    private final Entry[] slots;
    private final Map<Integer, Integer> slotOf = new HashMap<>();
    private long inserted;
    private long hits;
    private long misses;

    public FrameCache(DecodePipeline pipeline) {
        this(pipeline, DEFAULT_CAPACITY);
    }

    public FrameCache(DecodePipeline pipeline, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        }
        this.pipeline = pipeline;
        this.capacity = capacity;
        this.slots = new Entry[capacity];
    }

    /** Bytes the full ring holds for this header: packed pixels plus the block type map. */
    public static long bytesFor(Header h, int capacity) {
        return (long) capacity * (4L * h.width() * h.height() + h.blockCount());
    }

    /** Frame k: from the ring if present, otherwise decoded through the pipeline and remembered. */
    public Entry get(int k) throws IOException, InterruptedException {
        Integer slot = slotOf.get(k);
        if (slot != null) {
            hits++;
            return slots[slot];
        }
        misses++;
        return insert(k, pipeline.get(k));
    }

    /** True if frame k is in the ring or already decoded by the pipeline, so get(k) will not wait. */
    public boolean isReady(int k) {
        return slotOf.containsKey(k) || pipeline.isReady(k);
    }

    /** True if frame k is in the ring (no decode needed). */
    public boolean contains(int k) {
        return slotOf.containsKey(k);
    }

    private Entry insert(int k, DecodeCore.Decoded d) {
        int slot = (int) (inserted++ % capacity);
        Entry old = slots[slot];
        Types.Frame f = d.frame();
        int[] rgb;
        if (old != null) {
            slotOf.remove(old.index());
            rgb = old.rgb(); // reuse the evicted buffer: no 8 MB allocation per step at 1080p
        } else {
            rgb = new int[f.width() * f.height()];
        }
        pack(f, rgb);
        Entry e = new Entry(k, f.width(), f.height(), rgb, d.types());
        slots[slot] = e;
        slotOf.put(k, slot);
        return e;
    }

    private static void pack(Types.Frame f, int[] rgb) {
        int[] r = f.r();
        int[] g = f.g();
        int[] b = f.b();
        for (int i = 0; i < rgb.length; i++) {
            rgb[i] = (r[i] << 16) | (g[i] << 8) | b[i];
        }
    }

    public int capacity() {
        return capacity;
    }

    public long hits() {
        return hits;
    }

    public long misses() {
        return misses;
    }
}
