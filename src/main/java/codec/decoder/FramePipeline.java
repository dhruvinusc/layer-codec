package codec.decoder;

import codec.common.CmpFormat.Header;
import java.io.IOException;

/**
 * What the step-back cache and the player need from a decoder: exactly frame k, in any order,
 * with bounded memory. Two implementations: {@link DecodePipeline} for independent frames
 * (modes 0 and 1) and {@link GopPipeline} for motion-compensated groups (mode 2, P4-A).
 */
interface FramePipeline extends AutoCloseable {
    Header header();

    int frameCount();

    int capacity();

    int workers();

    /** Exactly frame k, waiting for it if needed. */
    DecodeCore.Decoded get(int k) throws IOException, InterruptedException;

    /** True if frame k is decoded and waiting. */
    boolean isReady(int k);

    /** Largest number of frames buffered at once. */
    int peakBuffered();

    /** Frames decoded so far, including discarded ones. */
    long decodedCount();

    @Override
    void close();
}
