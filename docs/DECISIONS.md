# Decisions

Project-level decisions D1 to D12 are owned by Project doc `claude/03-design-decisions-and-precautions.md`;
the full reasoning lives there. This file mirrors their status and records internal choices made while building
(`P<n>-D<k>: choice / reason / risk`). Changing an ADOPTED project decision needs a DECISION REQUEST.

## Project decisions (mirror of 03)
| ID | Decision | Status |
|---|---|---|
| D1 | Java 21 + Gradle wrapper; FFmpeg/ffprobe as an external process piping rgb24; Swing display; java.util.zip; JUnit 5; library calls only in VideoIO, Display, Zio | ADOPTED Nov 9 (professor confirmation pending) |
| D2 | Full search SAD reference; default 3-level hierarchical search, +/-8 coarse, +/-2 refine, predictor candidates, cost = SAD + lambda * L1 to median predictor | ADOPTED |
| D3 | Segmentation: reliability, RANSAC homography camera motion with affine/translation fallback, residual threshold, consistency-aware region growing, size/coherence filter, fill, 2-of-3 temporal vote; lean foreground | ADOPTED |
| D4 | Frame 0 reuses frame 1's labels | ADOPTED |
| D5 | Pad right/bottom by edge replication to multiples of 16; store true size, crop on decode | ADOPTED |
| D6 | q = round(c / 2^n), same step for all 64 coefficients; n in 0..10 | ADOPTED |
| D7 | Binary big-endian .cmp, full header, per-frame zlib level 1 with length prefix, `--raw` option, CmpDump shows brief text layout | ADOPTED |
| D8 | Decode-ahead threads, steady-clock Swing display, keys space/arrows/s/d/q, optional original mp4 for side-by-side | ADOPTED |
| D9 | Extra credit behind `--mode ec`: residual coding from reconstructed frame, zigzag + run-length | ADOPTED |
| D10 | RESULTS.md evidence set (PSNR per layer, sizes, timings, segmentation stats, images) | ADOPTED |
| D11 | ffprobe metadata; `-noautorotate` + explicit rotation filter; rgb24 pipe read with readNBytes to end of stream; BT.601 integer Y; audio ignored; streaming only | ADOPTED |
| D12 | MV convention: block at (x, y) in frame t matches (x + dx, y + dy) in frame t-1; arrows drawn along (-dx, -dy) | ADOPTED |

## Internal decisions
- P0-D1: n1, n2 limited to 0..10 / the largest DC magnitude of a level-shifted 8x8 block is 1024 = 2^10, so larger steps only zero every block / none.
- P0-D2: Gradle 9.9.0 wrapper, JUnit 5.11.4 BOM, jar name fixed to `layer-codec.jar` / launchers never change / none.
- P0-D3: Launchers pick Java as LAYER_CODEC_JAVA, then `java_home -v 21`, then JAVA_HOME, then PATH; heap -Xmx4g / the Mac default `java` was 20, which cannot run Java 21 bytecode / Windows launcher relies on JAVA_HOME or PATH.
- P0-D4: `vendorJunit` task copies the JUnit console jar to `build-tools/` / the build sandbox cannot reach Maven Central / two ways to run tests; Gradle on the Mac stays the authority.
- P0-D5: Naive baseline overlay will be regenerated in Java (`VizSeg --naive`, P1) as the fair same-code comparison; the PM prototype image is kept at `docs/img/naive_probe_overlay.jpg` and its script at `tools/prototype/probe_motion.py` for reference (moved from the repo root Nov 10) / same-code comparison is fairer / none.
- P0-D6: Rotation read from display-matrix side data `rotation` (counter-clockwise) first, legacy `TAG:rotate` (clockwise) as fallback; ffprobe output parsed as `-show_streams` key=value / FFmpeg 5+ dropped the tag and 4.4 (sandbox) vs 7.1 (Mac) name sections differently / a clip with a non-multiple-of-90 rotation is rejected with a clear error.
- P0-D7: Frame planes are `int[]` (0..255), not `short[]` / no casts in inner loops; 25 MB per padded 1080p frame and only about 3 frames are held / memory, acceptable.
- P0-D8: Display scales frames itself (integer bilinear, rows in parallel) into a screen-sized image and draws it 1:1 / Java2D scaling of an image written through its raster measured 48 ms per 1080p frame on the Mac (SAL 20.83 s for a 14.57 s clip, 100% late); our scaler measured 1.8 ms per frame in the sandbox / bilinear at 0.72 slightly softens fine detail, so judging compression artifacts needs the 1:1 zoom toggle planned in D8.
- P0-D9: Types.java interface (Frame int planes, VideoInfo, MotionField short dx/dy with D12 convention, Labels byte ids) APPROVED by Dhruvin Nov 10 / every later part builds on it / changes now need a DECISION REQUEST.
- P1-D1: Full search tie-break: lowest SAD, then smallest |dx|+|dy|, then first in scan order (dy then dx ascending); (0,0) is the starting best / flat or ambiguous blocks report (0,0) instead of an arbitrary offset, and output is deterministic / a block that truly moved over a flat area reports (0,0), which reliability (P1.4) marks unreliable anyway.
- P1-D2: Search window clamped so the reference block stays inside the padded frame; no out-of-frame candidates / no invented pixels, matches the oracle exactly / edge blocks see a smaller window, so motion leaving the frame can be missed at the border.
- P1-D3: SAD computed with an early exit once the running sum is strictly greater than the current best (checked per row) / same answer as a full SAD, about 3x faster on real clips / none; the oracle test compares against a version with no early exit.
- P1-D4: Thresholds live in `codec.common.Config` as one record per module (`Config.Motion` first) / one file to tune and to copy into RESULTS.md / none.
- P2-D1: [B/P2] DCT is orthonormal DCT-II (class formula, C(0) = 1/sqrt2), separable with a precomputed cosine table, level shift 128 inside `Dct`; coefficients stored row-major `coef[v*8+u]` (v vertical, u horizontal), no zigzag / matches the brief's natural order and JPEG convention; forward then inverse is exact to 1e-9 / none.
- P2-D2: [B/P2] `Dct.inverse` returns unrounded doubles with the level shift added back; rounding and clamping to 0..255 happen in Quant (P2.2) / keeps the DCT testable as lossless and puts every lossy step in one place / none.
- P2-D3: [B/P2] Decoder uses `Dct.inverseZeroSkip` (skips all-zero coefficient rows), proven bit-identical to `Dct.inverse` on 500 sparse blocks / quantized blocks are mostly zero rows, and serial 1080p decode is over budget (P0 speed gate) / speedup to be measured on the Mac in P2.9.
