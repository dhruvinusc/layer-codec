# 12 tests that matter

Run one test (from the repo folder):
`./gradlew test --tests 'codec.<package>.<Class>.<method>'`
Passing looks like: `BUILD SUCCESSFUL`. A failure prints the test name and the expected vs actual value.
Run all: `./gradlew test` (234 tests, HANDOFF).
Note: this single-test command form has not been run on the Mac yet. Try one before Dec 2.

| ID | Test | In plain words |
|---|---|---|
| B3 | `codec.encoder.MotionEstimatorTest.recoversKnownShiftsIncludingNegative` | Shift a picture by a known amount; the motion search finds that exact shift. |
| B6 | `codec.encoder.GlobalMotionTest.homographyWithThirtyPercentOutliersRecoveredWithinHalfPixel` | Camera motion is found even when 30% of the vectors are wrong (moving people). |
| B6 | `codec.encoder.SegmenterCameraTest.independentMoverIsNotExplained` | A block that moves on its own is not mistaken for camera motion. |
| B8 | `codec.encoder.SegmenterGroupTest.touchingPatchesMovingOppositeWaysBecomeTwoRegions` | Two touching objects moving apart become two foreground regions, not one. |
| C2 | `codec.common.DctTest.forwardThenInverseReturnsInput` | DCT then inverse DCT gives back the original 8x8 block. |
| C3 | `codec.common.QuantTest.errorIsAtMostHalfAStepAndGrowsWithN` | Dividing by 2^n and rounding loses at most half a step; bigger n loses more. |
| C4 | `codec.common.CmpFormatTest.blocksAreInScanLineOrder385BytesEach` | Blocks are written left to right, top to bottom, 1 + 3 x 64 values each. |
| D2 | `codec.decoder.DecodeCoreTest.blockTypeChoosesTheExponent` | The decoder uses n1 or n2 from each block's type byte. |
| D3 | `codec.decoder.DecodePipelineTest.everyFrameIdenticalToSerialDecode` | 4 workers in parallel give exactly the same frames as one worker. |
| E2 | `codec.decoder.PlayerTest.pauseStepAndStepBackMoveOneFrameAndStopTheClock` | Pause, step and step back each move one frame and stop the clock. |
| F1 | `codec.common.McCodecTest.encoderAndDecoderReferencesStayBitIdenticalOverSeveralGroups` | Mode 2: encoder and decoder keep the same reference frame, so errors do not build up. |
| F2 | `codec.common.RunLengthTest.roundTripIsLosslessForSparseDenseAndExtremeValues` | Mode 1: zigzag + run-length gives back every value exactly. |

Freeze guard: `codec.common.GoldenBaselineTest` checks the baseline file is byte for byte the same as the frozen one.
All names checked with grep in src/test/java on Nov 19 2026.
