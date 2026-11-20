# Requirements checklist

Every requirement from the project brief, whether it is met, and the proof. Tests live in `src/test/java/codec/` and all 234 pass (`./gradlew build`). Numbers point to sections of `docs/RESULTS.md`; images are in `docs/img/`.

## Command line

| ID | Requirement | Met | Proof |
|---|---|---|---|
| A1 | `myencoder input_video.mp4 n1 n2` writes `input_video.cmp` | Yes | `EncoderEndToEndTest.encodesEveryFrameWithTheHeaderTheDecoderNeeds`; all 4 clips encoded (RESULTS "Final check") |
| A2 | n1 is the foreground exponent, n2 the background exponent | Yes | `DecodeCoreTest.blockTypeChoosesTheExponent`; SAL (2,5) foreground 47.52 / background 34.34 dB, (5,2) 34.40 / 47.72 dB (RESULTS "Compression") |
| A3 | `mydecoder input_video.cmp` decodes and plays | Yes | `DecodePipelineTest.everyFrameIdenticalToSerialDecode`; 12 files played (RESULTS "Final check") |
| A4 | The decoder needs only the .cmp | Yes | The header holds size, padded size, frame rate, frame count, n1 and n2: `CmpFormatTest.headerIs48BytesWithPatchedCountsAndZeroReserved`; `DecoderMainTest.cmpOnly` |

## Segmentation

| ID | Requirement | Met | Proof |
|---|---|---|---|
| B1 | Frame 0 is an I frame | Yes | Every baseline frame is coded on its own; frame 0 reuses frame 1's labels: `SegmenterTemporalTest.runEmitsOneLabelSetPerFrameFrameZeroCopiesFrameOneAndIsDeterministic` |
| B2 | Later frames split into 16x16 macroblocks | Yes | `MotionEstimatorTest.recoversKnownShiftsIncludingNegative`; `PadTest.hd1080PadsBottomRowsByReplication` (1080 padded to 1088) |
| B3 | One motion vector per macroblock, against the previous frame | Yes | `MotionEstimatorTest.matchesTextbookOracleOnMixedContent`; `HierarchicalSearchTest.recovers30PixelShiftThatFullSearch16Cannot` |
| B4 | Block matching by MAD, brute force or a fast search | Yes | Full search kept as the reference, fast 3-level search by default; they agree on 89.9% to 95.9% of textured blocks (RESULTS "Segmentation") |
| B5 | Search on one component (Y preferred) | Yes | Search runs on Y only: `ColorConvTest.knownValues` |
| B6 | Background is zero motion or the camera's motion | Yes | Camera motion fitted as a homography, which includes the brief's single constant vector: `GlobalMotionTest.homographyWithThirtyPercentOutliersRecoveredWithinHalfPixel`, `SegmenterCameraTest.blockThatMovesWithTheCameraIsExplainedEvenIfItsOwnVectorIsWrong` |
| B7 | Foreground regions are contiguous and consistent in direction | Yes | `SegmenterGroupTest.consistencyRule`, `SegmenterGroupTest.randomDirectionAliasPatchIsDropped`; naive vs final on the same vectors: SAL 8.68% to 1.07% (RESULTS "Segmentation") |
| B8 | Several independently moving regions | Yes | `SegmenterGroupTest.touchingPatchesMovingOppositeWaysBecomeTwoRegions` |
| B9 | Labels from motion only, not content | Yes | Inputs are motion vectors, match errors and the camera model. Texture only decides whether a vector is trusted; no colour, shape or object detection. |
| B10 | Every macroblock of every frame is labelled | Yes | `SegmenterTemporalTest.runStatsCoverEveryFrame`, `LabelsIOTest.roundTripRandomAccessAndUnsignedIds` |
| B11 | Visual demarcation (green background, coloured foreground) | Yes | `VizSegTest.backgroundTintIsGreenAndForegroundRed`; `docs/img/p1_final_*.jpg` |

## Compression

| ID | Requirement | Met | Proof |
|---|---|---|---|
| C1 | 8x8 blocks inherit their 16x16 macroblock's label | Yes | `CmpFormatTest.blockTypeOf8x8IsItsMacroblockLabel`; 0 mismatches over 34.1 million blocks on the real files (RESULTS "Compression") |
| C2 | 8x8 DCT on R, G and B | Yes | `DctTest.matchesTextbookOracleOnRandomBlocks`, `FrameEncoderTest.roundTripAtN0IsNearlyExact`; 59 dB at n = 0 (RESULTS "Compression") |
| C3 | Uniform quantization, every step 2^n | Yes | `QuantTest.errorIsAtMostHalfAStepAndGrowsWithN`, `QuantTest.halvesRoundAwayFromZeroSymmetrically` |
| C4 | Blocks written in scan line order | Yes | `CmpFormatTest.blocksAreInScanLineOrder385BytesEach` |
| C5 | File holds n1, n2, then per block: type and 64 coefficients for each of 3 channels | Yes | `CmpFormatTest.threeFramesRoundTripIdenticallyWithAndWithoutZlib`; `CmpDumpTest.printsTheBriefsTextLayout` prints any frame in the brief's text layout |
| C6 | Baseline without zigzag or entropy coding is acceptable | Yes | Coefficients stored row by row; zlib is lossless packing only (`ZioTest.quantizedCoefficientsCompressWell`); baseline bytes pinned by `GoldenBaselineTest.baselineFileIsByteIdentical` |

## Decoder

| ID | Requirement | Met | Proof |
|---|---|---|---|
| D1 | Parses n1, n2, block types and coefficients | Yes | `CmpFormatTest.ruleMagicRejected` and the other rule tests; `DecodeCoreTest.returnsTheBlockTypeMap` |
| D2 | Dequantizes each block with n1 or n2 by its type | Yes | `DecodeCoreTest.storedThreeRebuildsAs12InForegroundAnd96InBackground` |
| D3 | Inverse DCT, rebuild, display | Yes | `DecodeCoreTest.nZeroRoundTripIsNearlyExactAndCropped`, `DctTest.zeroSkipInverseIsBitIdenticalToPlainInverse` |
| D4 | Plays at the original frame rate | Yes | `PlayerTest.slowDecodeDropsFramesButKeepsTheOriginalRate`, `ClockTest.ntscRationalRate`; all 12 files within 0.10% of the clip length (RESULTS "Final check") |
| D5 | Foreground cleaner when n1 < n2, reversed when swapped | Yes | SAL swap (RESULTS "Compression"); `docs/img/p3r_SAL_swap_crop_x3.png` |

## Player and time limit

| ID | Requirement | Met | Proof |
|---|---|---|---|
| E1 | Own display code for input and output | Yes | Own bilinear scaler: `DisplayScalerTest.halvingAveragesNeighbours`; Java2D scaling was 100% late, own scaler 0.5% (RESULTS "Decoder and playback") |
| E2 | Play, pause, step frame by frame | Yes | `PlayerTest.pauseStepAndStepBackMoveOneFrameAndStopTheClock`; step back: `FrameCacheTest.stepBackFiftyFrom100CrossesTheCacheBoundaryCorrectly` |
| E3 | Output can be compared with the original | Yes | Side by side, 1:1 zoom and foreground overlay: `DisplayViewTest.sideBySidePutsDecodedLeftAndOriginalRight`, `DisplayViewTest.zoomedSideBySideShowsTheSameRegionInBothHalves`; `docs/img/p3_SAL_n2_5_side_1to1.png` |
| E4 | Encode plus decode within 10 minutes | Yes | Worst case SAL (5,2): 130 s encode plus real-time playback (RESULTS "Final check") |

## Extra credit

| ID | Requirement | Met | Proof |
|---|---|---|---|
| F1 | Motion-compensated prediction: residual plus one vector per macroblock | Yes | Mode 2: `McCodecTest.encoderAndDecoderReferencesStayBitIdenticalOverSeveralGroups`; 36% to 51% smaller (RESULTS "Extra credit") |
| F2 | Better coefficient order (zigzag) | Yes | Mode 1: `RunLengthTest.zigzagIsTheJpegOrderAndVisitsEveryCoefficientOnce`; 29% to 40% smaller with identical pixels (RESULTS "Extra credit") |

## Where the brief is silent

Language and libraries, frame sizes not divisible by 16, rotated input, where size and frame rate are stored, text versus binary file, the label for frame 0, and audio. Each choice is in `docs/DECISIONS.md`.
