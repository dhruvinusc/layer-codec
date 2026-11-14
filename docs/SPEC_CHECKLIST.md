# Spec Checklist

Copy of Project doc `claude/01-requirements-checklist.md` (source of truth: Dhruvin-Project-Fall2026.docx).
A box is ticked only with an evidence path beside it: a test name, a line in RESULTS.md, or an image in `docs/img/`.
Due Wed Dec 2, 2026.

## A. Command line
- [ ] A1 `myencoder input_video.mp4 n1 n2` produces `input_video.cmp` (partial: stub validates args and derives the path, `EncoderMainTest.validArgsAreParsedAndOutputIsDerived`)
- [ ] A2 n1 = quantization exponent for FOREGROUND, n2 = for BACKGROUND (partial: CLI order and usage text, `EncoderMain.USAGE`)
- [x] A3 `mydecoder input_video.cmp` decodes and renders ([C/P3] DecoderMain.play; Mac playback RESULTS.md "P3.3 Player, Mac run 2" and "run 4"; DecodePipelineTest.everyFrameIdenticalToSerialDecode)
- [x] A4 Decoder needs nothing except the .cmp (all metadata in the file) ([C/P3] header carries size, padded size, fps, frame count, n1, n2 (03 D7); all playback, bench and error runs use the .cmp alone, original mp4 optional for side by side only; CmpFormatTest.headerIs48BytesWithPatchedCountsAndZeroReserved, DecoderMainErrorsTest)

## B. Part 1: Segmentation (encoder)
- [x] B1 Frame 0 treated as an I frame [A/P1.10]: frame 0 is coded on its own (every baseline frame is intra), and its labels reuse frame 1 (03 D4): SegmenterTemporalTest.runEmitsOneLabelSetPerFrameFrameZeroCopiesFrameOneAndIsDeterministic
- [x] B2 Every later frame split into 16x16 macroblocks [A/P1.10]: Types.MB = 16, padded grid 30x53 / 120x68 (RESULTS "P1.10"), MotionEstimatorTest
- [x] B3 One motion vector per macroblock, computed against the PREVIOUS frame [A/P1.10]: MotionEstimator.fullSearch / hierarchical against frame t-1, Segmenter.Run feeds consecutive frames; MotionEstimatorTest, RESULTS "P1.1", "P1.3"
- [x] B4 Block MAD matching, brute force or a fast search (FME is "even better") [A/P1.10]: full search (oracle) + hierarchical 3-level default; agreement 89.8 to 95.8%, real misses 1.6 to 4.0%; RESULTS "P1.3", P1-L7, P1-L10
- [x] B5 Search uses ONE component (Y preferred, R allowed) [A/P1.10]: Y from ColorConv.luma only (Segmenter.Run, VizSeg --stats)
- [x] B6 Background = near-zero MVs (static camera) or one constant MV (moving camera) [A/P1.10]: camera motion generalised from one constant MV to a RANSAC homography (all 4 clips have a moving camera); background = residual within 2.5 px or explained by the camera; GlobalMotionTest, RESULTS "P1.5", P1-L11, P1-L15
- [x] B7 Foreground regions are (a) contiguous and (b) consistent in MV direction ("important!") [A/P1.10]: 4-connected growth only through neighbours whose residuals agree, robust spread filter; SegmenterGroupTest, RESULTS "P1.6", P1-L13, P1-L14
- [x] B8 Several independently moving regions supported [A/P1.10]: SegmenterGroupTest.touchingPatchesMovingOppositeWaysBecomeTwoRegions; up to 7.53 regions/frame on video2 (RESULTS "P1.10")
- [x] B9 Classification from motion only, not content [A/P1.10]: inputs are vectors, match errors (SAD) and the camera model; no colour, shape or detector; texture only gates whether a vector is trusted (P1-L9)
- [x] B10 Every macroblock of every frame has a foreground/background label [A/P1.10]: labels files cover every frame (518, 383, 346, 437 frames), LabelsIOTest, RESULTS "P1.10"
- [x] B11 Visual demarcation like the brief's figure (green background, red/blue foreground) [A/P1.10]: VizSeg tint, docs/img/p1_final_*.jpg (and naive_*, p16_* to p19_* for before/after)

## C. Part 2: Compression (encoder)
- [ ] C1 Frame split into 8x8 blocks; each inherits its 16x16 macroblock's label (partial [B/P2]: fake labels; final check after wire-up. CmpFormat.blockTypes, CmpFormatTest.blockTypeOf8x8IsItsMacroblockLabel, FrameEncoderTest.blockTypesFollowLabelsAndRegionIdsCollapseToOne)
- [x] C2 8x8 DCT on all three channels (R, G, B) ([B/P2] DctTest.matchesTextbookOracleOnRandomBlocks; FrameEncoder DCT per R, G, B; FrameEncoderTest.roundTripAtN0IsNearlyExact; RESULTS.md 'P2.6 CodecHarness first real-clip runs' (n=0 59 dB on video2 and SAL))
- [x] C3 Uniform quantization, every table entry 2^n (n1 foreground, n2 background) ([B/P2] QuantTest.errorIsAtMostHalfAStepAndGrowsWithN; DecodeCoreTest.blockTypeChoosesTheExponent; CodecHarnessTest.syntheticClipEndToEndIsDeterministicAndN0IsNearlyLossless; RESULTS.md P2.6 (video2 (2,5) fg 47.47 / bg 32.67 dB); docs/img/p2_SAL_n1_7_checker_crop1to1.png)
- [x] C4 Blocks written in scan line order ([B/P2] CmpFormatTest.blocksAreInScanLineOrder385BytesEach; FrameEncoder block index b -> (b % blocksX, b / blocksX); real frames round trip in RESULTS.md P2.6)
- [x] C5 File holds n1, n2, then per block: block_type + 64 coefficients x 3 channels ([B/P2] CmpFormat v1 (03 D7); CmpFormatTest.headerIs48BytesWithPatchedCountsAndZeroReserved, threeFramesRoundTripIdenticallyWithAndWithoutZlib; real .cmp files in RESULTS.md P2.6)
- [x] C6 Baseline without zigzag, intermediary representation, or entropy coding is acceptable ([B/P2] coefficients row-major, no zigzag (P2-D1); zlib is lossless byte packing only (03 D7, ZioTest); baseline mode 0)

## D. Part 3: Decoder
- [x] D1 Parses n1, n2, block types, coefficients ([B/P2] CmpFormat.Reader + validation tests rule*; [C/P3] DecodeCoreTest.returnsTheBlockTypeMap, DecodePipelineTest.everyFrameIdenticalToSerialDecode, DecoderMainErrorsTest)
- [x] D2 Dequantizes each block with n1 or n2 by block_type ([C/P3] DecodeCoreTest.blockTypeChoosesTheExponent, DecodeCoreTest.storedThreeRebuildsAs12InForegroundAnd96InBackground; RESULTS.md P2.9 fg/bg PSNR)
- [x] D3 Inverse DCT, reconstruct, display ([C/P3] DctTest.zeroSkipInverseIsBitIdenticalToPlainInverse, DecodeCoreTest.nZeroRoundTripIsNearlyExactAndCropped, Player + Display on the Mac: RESULTS.md P3.3; docs/img/p3_SAL_n2_5_side_1to1.png)
- [x] D4 Plays at the ORIGINAL frame rate ([C/P3] RESULTS.md "P3.3 Player, Mac run 2" (all clips within 0.6%, video2 12.77 s vs 12.77 s) and "run 4" (0 drops, within 0.13%); PlayerTest.fastDecodePlaysEveryFrameOnTimeAndPausesOnTheLast, slowDecodeDropsFramesButKeepsTheOriginalRate)
- [ ] D5 Foreground visibly cleaner than background when n1 < n2; effect reverses when swapped (partial [C/P3]: fake checkerboard labels; docs/img/p3_SAL_n2_5_side_1to1.png vs p3_SAL_n5_2_side_1to1.png (decoder 1:1 side by side), RESULTS.md P2.9 fg 47.7 vs bg 34.5 dB at (2,5), reversed at (5,2); final check with real labels after wire-up, P3.R)

## E. Player and run time
- [x] E1 Own display code for input and output video ([C/P3] Display: own bilinear scaler (P0-D8), packed path, Display.View; DisplayScalerTest, DisplayViewTest; original read by OriginalVideo, OriginalVideoTest)
- [x] E2 Play, PAUSE, STEP frame by frame ([C/P3] Mac keys check Nov 12 (WORKLOG [C/P3] "P3.3 DONE"); PlayerTest.pauseStepAndStepBackMoveOneFrameAndStopTheClock; step back: FrameCacheTest.stepBackFiftyFrom100CrossesTheCacheBoundaryCorrectly)
- [x] E3 Output can be compared against the original ([C/P3] side by side + 1:1 zoom + overlay: docs/img/p3_SAL_n2_5_side_1to1.png, p3_SAL_n5_2_side_1to1.png, p3_video2_n2_5_overlay.png; Mac view check and 0 drops with the original at 30.0 fps: RESULTS.md "P3.4 / P3.4b Mac checks, Nov 13")
- [ ] E4 Encode + decode finish within 10 minutes (partial [C/P3]: decode faster than real time, pipeline 40.7 to 60.7 fps at 1080p, RESULTS.md P3.1; encode side and real files after wire-up, P3.R)

## F. Extra credit (only after A to E are green)
- [ ] F1 Motion-compensated prediction: encode residual + one MV per macroblock
- [ ] F2 Better coefficient scan order (zigzag) to help compression

## G. Brief is silent; decided in DECISIONS.md
Language and libraries (D1), sizes not divisible by 16 (D5), where size/fps/frame count live (D7), text vs binary .cmp (D7), frame 0 label (D4), rotation metadata (D11), audio (D11), report and presentation format, demo logistics.
