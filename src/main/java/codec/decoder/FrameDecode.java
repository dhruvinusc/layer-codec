package codec.decoder;

import codec.common.CmpFormat;
import codec.common.CmpFormat.CmpException;
import codec.common.CmpFormat.Header;
import codec.common.RunLength;
import codec.common.Types;

/**
 * Decodes one frame payload whatever the file's mode (P4-B): mode 0 baseline goes to
 * {@link DecodeCore} unchanged; mode 1 (zigzag + run-length) is unpacked into the same block
 * types and coefficients, then reconstructed by the same dequantize + inverse DCT code, so both
 * modes give identical pixels for identical coefficients. Thread-safe like DecodeCore: all
 * working memory is the caller's Scratch.
 */
final class FrameDecode {

    private FrameDecode() {
    }

    static DecodeCore.Decoded decode(Header h, byte[] payload, DecodeCore.Scratch s) throws CmpException {
        if (h.mode() == CmpFormat.MODE_BASELINE) {
            return DecodeCore.decode(h, payload, s);
        }
        if (h.mode() != CmpFormat.MODE_RLE) {
            throw new CmpException("unknown mode " + h.mode());
        }
        RunLength.decode(h, payload, s.types, s.coef);
        Types.Frame out = Types.Frame.allocate(h.width(), h.height());
        DecodeCore.reconstructRows(h, s.types, s.coef, out, 0, h.blocksY(), s.c, s.px, s.tmp);
        return new DecodeCore.Decoded(out, s.types.clone());
    }
}
