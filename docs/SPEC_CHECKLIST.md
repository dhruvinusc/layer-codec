# Spec Checklist

Copy of Project doc `claude/01-requirements-checklist.md` (source of truth: Dhruvin-Project-Fall2026.docx).
A box is ticked only with an evidence path beside it: a test name, a line in RESULTS.md, or an image in `docs/img/`.
Due Wed Dec 2, 2026.

## A. Command line
- [ ] A1 `myencoder input_video.mp4 n1 n2` produces `input_video.cmp` (partial: stub validates args and derives the path, `EncoderMainTest.validArgsAreParsedAndOutputIsDerived`)
- [ ] A2 n1 = quantization exponent for FOREGROUND, n2 = for BACKGROUND (partial: CLI order and usage text, `EncoderMain.USAGE`)
- [ ] A3 `mydecoder input_video.cmp` decodes and renders
- [ ] A4 Decoder needs nothing except the .cmp (all metadata in the file)

## B. Part 1: Segmentation (encoder)
- [ ] B1 Frame 0 treated as an I frame
- [ ] B2 Every later frame split into 16x16 macroblocks
- [ ] B3 One motion vector per macroblock, computed against the PREVIOUS frame (partial: MotionEstimator.fullSearch, MotionEstimatorTest.recoversKnownShiftsIncludingNegative; wired per frame in P1.10)
- [ ] B4 Block MAD matching, brute force or a fast search (FME is "even better") (partial: brute force done, MotionEstimatorTest.matchesTextbookOracleOnMixedContent, RESULTS P1.1; fast search P1.3)
- [ ] B5 Search uses ONE component (Y preferred, R allowed) (partial: fullSearch takes the Y plane from ColorConv.luma; wired in P1.10)
- [ ] B6 Background = near-zero MVs (static camera) or one constant MV (moving camera)
- [ ] B7 Foreground regions are (a) contiguous and (b) consistent in MV direction ("important!")
- [ ] B8 Several independently moving regions supported
- [ ] B9 Classification from motion only, not content
- [ ] B10 Every macroblock of every frame has a foreground/background label
- [ ] B11 Visual demarcation like the brief's figure (green background, red/blue foreground)

## C. Part 2: Compression (encoder)
- [ ] C1 Frame split into 8x8 blocks; each inherits its 16x16 macroblock's label (partial [B/P2]: fake labels; final check after wire-up. CmpFormat.blockTypes, CmpFormatTest.blockTypeOf8x8IsItsMacroblockLabel, FrameEncoderTest.blockTypesFollowLabelsAndRegionIdsCollapseToOne)
- [x] C2 8x8 DCT on all three channels (R, G, B) ([B/P2] DctTest.matchesTextbookOracleOnRandomBlocks; FrameEncoder DCT per R, G, B; FrameEncoderTest.roundTripAtN0IsNearlyExact; RESULTS.md 'P2.6 CodecHarness first real-clip runs' (n=0 59 dB on video2 and SAL))
- [x] C3 Uniform quantization, every table entry 2^n (n1 foreground, n2 background) ([B/P2] QuantTest.errorIsAtMostHalfAStepAndGrowsWithN; DecodeCoreTest.blockTypeChoosesTheExponent; CodecHarnessTest.syntheticClipEndToEndIsDeterministicAndN0IsNearlyLossless; RESULTS.md P2.6 (video2 (2,5) fg 47.47 / bg 32.67 dB); docs/img/p2_SAL_n1_7_checker_crop1to1.png)
- [x] C4 Blocks written in scan line order ([B/P2] CmpFormatTest.blocksAreInScanLineOrder385BytesEach; FrameEncoder block index b -> (b % blocksX, b / blocksX); real frames round trip in RESULTS.md P2.6)
- [x] C5 File holds n1, n2, then per block: block_type + 64 coefficients x 3 channels ([B/P2] CmpFormat v1 (03 D7); CmpFormatTest.headerIs48BytesWithPatchedCountsAndZeroReserved, threeFramesRoundTripIdenticallyWithAndWithoutZlib; real .cmp files in RESULTS.md P2.6)
- [x] C6 Baseline without zigzag, intermediary representation, or entropy coding is acceptable ([B/P2] coefficients row-major, no zigzag (P2-D1); zlib is lossless byte packing only (03 D7, ZioTest); baseline mode 0)

## D. Part 3: Decoder
- [ ] D1 Parses n1, n2, block types, coefficients (partial [B/P2]: CmpFormat.Reader + validation tests rule*)
- [ ] D2 Dequantizes each block with n1 or n2 by block_type
- [ ] D3 Inverse DCT, reconstruct, display
- [ ] D4 Plays at the ORIGINAL frame rate
- [ ] D5 Foreground visibly cleaner than background when n1 < n2; effect reverses when swapped

## E. Player and run time
- [ ] E1 Own display code for input and output video
- [ ] E2 Play, PAUSE, STEP frame by frame
- [ ] E3 Output can be compared against the original
- [ ] E4 Encode + decode finish within 10 minutes

## F. Extra credit (only after A to E are green)
- [ ] F1 Motion-compensated prediction: encode residual + one MV per macroblock
- [ ] F2 Better coefficient scan order (zigzag) to help compression

## G. Brief is silent; decided in DECISIONS.md
Language and libraries (D1), sizes not divisible by 16 (D5), where size/fps/frame count live (D7), text vs binary .cmp (D7), frame 0 label (D4), rotation metadata (D11), audio (D11), report and presentation format, demo logistics.
