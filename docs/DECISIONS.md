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
- P0-D5: Naive baseline overlay will be regenerated in Java (`VizSeg --naive`, P1) instead of using the PM prototype image / prototype files were not available, and same-code comparison is fairer / none.
