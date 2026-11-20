package codec.common;

/**
 * Uniform quantization with step 2^n for all 64 coefficients (brief part 2, checklist C3).
 * n1 is used for foreground blocks and n2 for background blocks; the caller picks n by block_type.
 *
 * <p>This is the ONLY lossy step in the codec (03 D6). Encode: q = round(c / 2^n), stored as a
 * short. Decode: c' = q * 2^n. Rounding is symmetric, half away from zero, so a negative
 * coefficient loses exactly as much as the matching positive one, and the average error is half
 * of what truncation would give (tested).
 */
public final class Quant {

    /** Smallest allowed exponent: step 1, only rounding to whole numbers is lost. */
    public static final int MIN_N = 0;

    /** Largest useful exponent (P0-D1): |DC| <= 1024 = 2^10, so larger steps change nothing. */
    public static final int MAX_N = 10;

    private Quant() {
    }

    /** Quantizer step 2^n. */
    public static int step(int n) {
        checkN(n);
        return 1 << n;
    }

    /** q = round(c / 2^n), half away from zero. Throws if q would not fit a short. */
    public static short quantize(double c, int n) {
        double x = c / step(n);
        long q = (long) Math.floor(Math.abs(x) + 0.5);
        if (x < 0) {
            q = -q;
        }
        if (q < Short.MIN_VALUE || q > Short.MAX_VALUE) {
            throw new ArithmeticException("quantized value " + q + " does not fit a short (c=" + c + ", n=" + n + ")");
        }
        return (short) q;
    }

    /** c' = q * 2^n. */
    public static double dequantize(short q, int n) {
        return q * (double) step(n);
    }

    /** Quantizes 64 coefficients into {@code out[off .. off+63]}. */
    public static void quantizeBlock(double[] coef, int n, short[] out, int off) {
        for (int i = 0; i < 64; i++) {
            out[off + i] = quantize(coef[i], n);
        }
    }

    /** Dequantizes {@code q[off .. off+63]} into 64 coefficients. */
    public static void dequantizeBlock(short[] q, int off, int n, double[] coef) {
        double s = step(n);
        for (int i = 0; i < 64; i++) {
            coef[i] = q[off + i] * s;
        }
    }

    /**
     * Inverse DCT output to a displayable pixel: round to nearest, clamp to 0..255.
     * Clamping is needed because quantization error can push values slightly outside the range.
     */
    public static int toPixel(double v) {
        int p = (int) Math.floor(v + 0.5);
        return p < 0 ? 0 : Math.min(p, 255);
    }

    private static void checkN(int n) {
        if (n < MIN_N || n > MAX_N) {
            throw new IllegalArgumentException("n must be " + MIN_N + ".." + MAX_N + ", got " + n);
        }
    }
}
