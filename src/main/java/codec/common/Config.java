package codec.common;

/**
 * Tunable constants, one record per module (00 rules section 5: no magic numbers).
 *
 * <p>Every threshold the encoder uses lives here so tuning (P1.9) touches one file and the
 * final values can be copied into RESULTS.md as they are.
 */
public final class Config {

    private Config() {
    }

    /**
     * Motion estimation settings.
     *
     * @param fullRange search range in pixels for the full search reference: every offset with
     *                  |dx| and |dy| up to this value is tried (03 D2)
     */
    public record Motion(int fullRange) {
        public static final Motion DEFAULT = new Motion(16);

        public Motion {
            if (fullRange < 0 || fullRange > 64) {
                throw new IllegalArgumentException("fullRange must be 0..64, got " + fullRange);
            }
        }
    }
}
