package codec.common;

/**
 * Shared data types for the whole codec.
 *
 * <p>INTERFACE: changing anything here needs a DECISION REQUEST (00 rules section 5).
 * Records hold arrays, so record equals/hashCode compare array references, not contents.
 */
public final class Types {

    /** Macroblock size used for motion estimation and labels (brief, part 1). */
    public static final int MB = 16;

    /** DCT block size (brief, part 2). Each 8x8 block inherits the label of its 16x16 macroblock. */
    public static final int BLK = 8;

    private Types() {
    }

    /** Rounds {@code n} up to the next multiple of {@link #MB} (03 D5: 1080 becomes 1088). */
    public static int paddedSize(int n) {
        return (n + MB - 1) / MB * MB;
    }

    /**
     * Planar RGB frame, row-major, {@code width * height} samples per plane.
     *
     * <p>Samples are 0..255 stored as {@code int}. Planar because the DCT runs per channel and
     * motion search runs on one plane. Unsigned ints so the Java signed-byte trap is handled once,
     * in {@link VideoIO}, and never again.
     */
    public record Frame(int width, int height, int[] r, int[] g, int[] b) {
        public Frame {
            int n = width * height;
            if (width <= 0 || height <= 0 || r.length != n || g.length != n || b.length != n) {
                throw new IllegalArgumentException("plane sizes do not match " + width + "x" + height);
            }
        }

        public static Frame allocate(int width, int height) {
            int n = width * height;
            return new Frame(width, height, new int[n], new int[n], new int[n]);
        }
    }

    /**
     * Facts about a clip.
     *
     * @param width      UPRIGHT width, after rotation is applied
     * @param height     UPRIGHT height
     * @param paddedW    width rounded up to a multiple of 16
     * @param paddedH    height rounded up to a multiple of 16
     * @param fpsNum     container frame rate numerator (play at fpsNum / fpsDen)
     * @param fpsDen     container frame rate denominator
     * @param frameCount frame count from the container tag, -1 if absent. A hint only: readers
     *                   always read to end of stream (03 D11)
     * @param rotationCw degrees clockwise applied when reading: 0, 90, 180 or 270
     */
    public record VideoInfo(int width, int height, int paddedW, int paddedH,
                            int fpsNum, int fpsDen, int frameCount, int rotationCw) {
        public double fps() {
            return (double) fpsNum / fpsDen;
        }

        public int mbCols() {
            return paddedW / MB;
        }

        public int mbRows() {
            return paddedH / MB;
        }
    }

    /**
     * One motion vector per macroblock, row-major over the macroblock grid.
     *
     * <p>Convention (03 D12): the block at (x, y) in frame t best matches the block at
     * (x + dx, y + dy) in frame t-1. The content therefore moved by (-dx, -dy), which is the
     * direction overlay arrows are drawn.
     */
    public record MotionField(int cols, int rows, short[] dx, short[] dy) {
        public MotionField {
            if (dx.length != cols * rows || dy.length != cols * rows) {
                throw new IllegalArgumentException("vector arrays do not match " + cols + "x" + rows);
            }
        }
    }

    /**
     * One label per macroblock, row-major. 0 = background, 1..255 = foreground region id.
     * Always read with {@code id[i] & 0xFF}.
     */
    public record Labels(int cols, int rows, byte[] id) {
        public Labels {
            if (id.length != cols * rows) {
                throw new IllegalArgumentException("label array does not match " + cols + "x" + rows);
            }
        }

        public int get(int col, int row) {
            return id[row * cols + col] & 0xFF;
        }
    }
}
