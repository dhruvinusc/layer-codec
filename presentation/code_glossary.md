# Code glossary: every job, where it lives

Use: the script tags each job as **[J5]** etc. When the professor says "show me", find the J number here, open the file, jump to the line (VS Code: Cmd+P, type the file name, then `:112`), and say the plain sentence. Never read code aloud.

Paths are under `src/main/java/codec/` unless written in full. Line numbers are for commit 47f6d73 (tag `docs-polish-1119`). That commit only rewrote comments after the freeze, so the code itself is identical to `freeze-code` (88fc181); only line positions moved in EncoderMain, Segmenter and DecoderMain.
Run one test: `./gradlew test --tests '<TestClass>'` (or `'<TestClass>.<method>'`).

---

## ENCODER: the five things it does

### Whole encoder in one place
| J | Job | File : lines | Say this | Test |
|---|---|---|---|---|
| J0 | The encoder from start to end | `encoder/EncoderMain.java` 74 to 98 | "Line 81 opens the video, 84 starts segmentation, 87 opens the output file, 90 pads each frame, 93 gives its brightness to segmentation, 132 compresses a frame once its labels are ready." | `EncoderEndToEndTest` |
| J0b | Command line and output name | `encoder/EncoderMain.java` 55, 190 to 194 | "Exactly `myencoder file.mp4 n1 n2`; the output is the same name with .cmp." | `EncoderMainTest` |

### Thing 1: read the video
| J | Job | File : lines | Say this | Test |
|---|---|---|---|---|
| J1 | Read mp4 frames | `common/VideoIO.java` 128 to 134, 150 to 155 | "FFmpeg is started as a separate program and sends raw RGB pixels through a pipe; I read one frame's worth of bytes at a time." | `VideoIOTest` |
| J1b | Bytes to pixel values | `common/VideoIO.java` 107 to 114 | "Java bytes go from minus 128 to 127, so `& 0xFF` turns them back into 0 to 255." | `VideoIOTest` |
| J2 | Turn the frame upright | `common/VideoIO.java` 61, 92 to 98 | "Some phones store the video upside down with a note; I read the note and rotate explicitly." | `VideoIOTest` |
| J3 | Pad to a multiple of 16 | `common/Pad.java` 18, 34 | "Copy the edge rows until 1080 becomes 1088." | `PadTest` |
| J4 | Brightness (Y) | `common/ColorConv.java` 21 to 22 | "Brightness is 30% red, 59% green, 11% blue, done in whole numbers." | `ColorConvTest` |

### Thing 2: find the arrows (motion vectors)
| J | Job | File : lines | Say this | Test |
|---|---|---|---|---|
| J5 | How well two squares match | `encoder/MotionEstimator.java` 112 to 125 | "Add up the brightness differences of 256 pixels; smaller is a better match. Stop early if it is already worse than the best." | `MotionEstimatorTest.sadZeroAndMadAreReportedCorrectly` |
| J6 | Exact search (the reference) | `encoder/MotionEstimator.java` 53, 70 to 104 | "Try every position within the range and keep the best. Slow, so used only to check the fast one." | `MotionEstimatorTest.recoversKnownShiftsIncludingNegative` |
| J7 | Fast search (zoom out, then in) | `encoder/MotionEstimator.java` 161, 338 | "Shrink the picture twice, search widely on the tiny one, then refine at each bigger size." | `HierarchicalSearchTest` |
| J8 | Prefer arrows that agree with neighbours | `encoder/MotionEstimator.java` 183 to 184, 310 | "Score = match error plus a small penalty for disagreeing with the neighbours' middle arrow. Stops bricks from picking a wrong arrow." | `HierarchicalSearchTest` |

### Thing 3: decide what is moving (labels)
| J | Job | File : lines | Say this | Test |
|---|---|---|---|---|
| J21 | The whole chain for one frame | `encoder/Segmenter.java` 574 to 579 | "574 arrows, 576 which arrows to trust, 577 camera motion, 578 candidates, groups and gap filling, 579 the two of three frames vote." | `EncoderEndToEndTest` |
| J9 | Which arrows to trust | `encoder/Segmenter.java` 65 to 77 (line 73) | "Trust an arrow only if the square has some texture and matched well." | `SegmenterReliabilityTest` |
| J10 | Find the camera's motion (RANSAC) | `encoder/GlobalMotion.java` 81, 127 to 175 | "Pick 4 random squares, build a camera rule, count who agrees; 500 times with a fixed seed; keep the best." | `GlobalMotionTest.homographyWithThirtyPercentOutliersRecoveredWithinHalfPixel` |
| J11 | Build the camera rule from 4 points | `encoder/GlobalMotion.java` 235, 341 | "Four point pairs give 8 equations for the 8 numbers; solved by Gaussian elimination." | `GlobalMotionTest.fourExactPointsGiveTheExactHomographyAtFullHdScale` |
| J12 | Simpler fallbacks | `encoder/GlobalMotion.java` 115 to 123 | "If the full camera rule fits too few squares, try a simpler one, then a single arrow." | `GlobalMotionTest.collinearPointsFallBackToTranslation` |
| J13 | Leftover motion | `encoder/Segmenter.java` 87 to 98 | "Square's arrow minus where the camera says it should have gone." | `SegmenterGroupTest` |
| J14 | Candidate test | `encoder/Segmenter.java` 174 to 182 | "A candidate must have big leftover motion, and the camera's own arrow must not fit it almost as well." | `SegmenterCameraTest` |
| J15 | Group candidates into regions | `encoder/Segmenter.java` 193, 218 | "Flood fill from each candidate to its four neighbours, like BFS." | `SegmenterGroupTest` |
| J16 | Neighbour agreement rule | `encoder/Segmenter.java` 308 to 320 | "Two neighbours join if their leftover motions are close, or both big and pointing the same way." | `SegmenterGroupTest` |
| J17 | Drop noisy regions | `encoder/Segmenter.java` 327 | "A region whose arrows are scattered, or that is tiny, is noise." | `SegmenterGroupTest` |
| J18 | Fill gaps and holes | `encoder/Segmenter.java` 363, 375, 403 | "A square surrounded on 3 of 4 sides by one region joins it; enclosed holes are filled." | `SegmenterFillTest` |
| J19 | Two of three frames vote | `encoder/Segmenter.java` 499 to 511 | "Look at the same square in the previous and next frame, following its arrow; two or more votes wins." | `SegmenterTemporalTest` |
| J20 | Frame 0 | `encoder/Segmenter.java` 610 | "The first frame has no previous frame, so it reuses frame 1's labels." | `SegmenterTemporalTest` |
| J22 | Parallax filter (off) | `encoder/Segmenter.java` 265 | "Removes regions moving along the camera's flow. Off, because it also removed a real person." | `SegmenterParallaxTest` |
| J23 | The naive rule (for comparison) | `encoder/Segmenter.java` 106 | "Big leftover motion and at least 4 squares together. What my rules beat." | `SegmenterNaiveTest` |

### Thing 4: compress each square
| J | Job | File : lines | Say this | Test |
|---|---|---|---|---|
| J24 | 8x8 square takes its 16x16 label | `common/CmpFormat.java` 137 to 146 | "Divide the position by 2 to find the big square, copy its label." | `FrameEncoderTest` |
| J27 | The per-square loop | `common/FrameEncoder.java` 86 to 100 | "89 picks n1 or n2 from the label, then 93 to 98 do DCT and rounding for red, green, blue." | `FrameEncoderTest` |
| J25 | DCT | `common/Dct.java` 51 to 62 | "Shift pixels by 128, then rewrite the 64 pixels as 64 pattern weights, rows first, then columns." | `DctTest.forwardThenInverseReturnsInput` |
| J26 | Divide by 2^n and round | `common/Quant.java` 26, 30 to 40 | "Step is 2 to the n; divide, round to nearest. The only loss." | `QuantTest.halvesRoundAwayFromZeroSymmetrically` |

### Thing 5: write the file
| J | Job | File : lines | Say this | Test |
|---|---|---|---|---|
| J28 | Pack one frame | `common/CmpFormat.java` 149 | "Per square: one type byte, then 64 red, 64 green, 64 blue numbers, in scan line order." | `CmpFormatTest.blocksAreInScanLineOrder385BytesEach` |
| J28b | Zip the frame | `common/Zio.java` 28 to 44 | "Ordinary lossless zip on each frame." | `ZioTest` |
| J29 | Header | `common/CmpFormat.java` 188 to 193 | "48 bytes: a tag 'LYRC', size, frame rate, frame count, n1, n2." | `CmpFormatTest` |
| J30 | Frame table at the end | `common/CmpFormat.java` 275 to 291 | "When closing, write where each frame starts, so the player can jump back." | `CmpFormatTest` |

---

## DECODER AND PLAYER
| J | Job | File : lines | Say this | Test |
|---|---|---|---|---|
| J31 | The decoder from start to end | `decoder/DecoderMain.java` 99 to 124 | "102 picks the number of threads, 110 opens the original if given, 117 starts decoding, 124 starts the player." | `DecoderMainTest` |
| J32 | Read and check the header | `common/CmpFormat.java` 200, 218 to 220 | "Check the tag and the values; refuse a broken or unfinished file with a clear message." | `DecoderMainErrorsTest` |
| J33 | Fetch and unzip one frame | `common/CmpFormat.java` 365; `common/Zio.java` 52 to 73 | "Jump to the frame using the table, unzip it." | `ZioTest` |
| J34 | Pick the step from the type byte | `decoder/DecodeCore.java` 97 | "Byte says moving: use n1. Otherwise n2." | `DecodeCoreTest.blockTypeChoosesTheExponent` |
| J35 | Multiply back, undo DCT, clamp | `decoder/DecodeCore.java` 108 to 117 | "110 multiplies by the step, 111 undoes the DCT, 117 rounds and keeps pixels in 0 to 255." | `FrameDecodeTest` |
| J36 | Cut off the padding | `decoder/DecodeCore.java` 90 | "Only the true picture size is written back, so the padding never shows." | `FrameDecodeTest` |
| J37 | Several frames at once, kept in order | `decoder/DecodePipeline.java` 81, 144 to 156 | "Four threads decode whole frames; the player waits for exactly the frame number it needs." | `DecodePipelineTest.everyFrameIdenticalToSerialDecode` |
| J38 | Play on a clock, skip late frames | `decoder/Player.java` 196 to 214 | "204 waits until the frame is due; 208 to 209 work out which frame should be on screen now; 210 to 214 count anything skipped." | `PlayerTest.slowDecodeDropsFramesButKeepsTheOriginalRate` |
| J39 | Pause, step, step back keys | `decoder/Player.java` 235 to 254 | "Space, right, left each change one frame and stop the clock." | `PlayerTest.pauseStepAndStepBackMoveOneFrameAndStopTheClock` |
| J40 | Remember the last 30 frames | `decoder/FrameCache.java` 30, 75 | "One second of frames kept so step back is instant; older ones are decoded again." | `FrameCacheTest` |
| J41 | Side by side with the original | `decoder/OriginalVideo.java` 29; `common/Display.java` 185 | "Reads the original mp4 frame by frame so both sides step together." | `OriginalVideoTest` |
| J42 | Fit to screen and 1:1 zoom | `common/Display.java` 71, 259 | "My own scaler shrinks 1080p to fit; z shows real pixels so the blocks are visible." | `DisplayScalerTest` |
| J43 | Red tint on moving squares | `common/Display.java` 478 to 487 | "Mix 3/8 red into every square whose type byte says moving." | `DisplayViewTest` |

---

## EXTRA CREDIT
| J | Job | File : lines | Say this | Test |
|---|---|---|---|---|
| J44 | Zigzag order | `common/RunLength.java` 27, 32 to 45 | "Walk the 8x8 weights diagonal by diagonal, smooth to busy." | `RunLengthTest` |
| J45 | Count zeros instead of storing them | `common/RunLength.java` 62 to 78 | "Store 'how many zeros, then the value'; end each square with one marker." | `RunLengthTest` |
| J46 | Store the difference from the last frame | `common/McCodec.java` 34, 113, 118 | "Every second frame: move the previous decoded square by its arrow, subtract, compress only the difference." | `McCodecTest`, `GopPipelineTest` |
| J47 | Default file never changed | `src/test/java/codec/common/GoldenBaselineTest.java` | "Compares today's normal output byte for byte with a saved copy." | `GoldenBaselineTest` |

## TOOLS AND LAB
| J | Job | File : lines | Say this | Test |
|---|---|---|---|---|
| J48 | Print the file in the brief's text layout | `tools/CmpDump.java` 60 | "Type byte then 64, 64, 64 numbers per square, as text." | `CmpDumpTest` |
| J49 | Quality per layer (PSNR) | `tools/Psnr.java` 36, 62 | "62 sorts each pixel into moving or still by its square's type byte; 36 turns the error into decibels." | `PsnrTest` |
| J50 | Pick n2 for a bit budget (lab) | `lab/rd/src/lab/rd/RateControl.java` 125 to 141 | "Keep n1 fixed, binary search for the finest n2 that fits the budget." | lab only |

Note on J10: file `src/main/java/codec/encoder/GlobalMotion.java`, line 81 (`fit`), 127 to 175 (`ransac`: 130 fixed seed, 136 the 500 tries), 417 (count who agrees).

---

## Slide to job map (quick lookup during Q&A)
| Slide | Jobs |
|---|---|
| 1 Problem | J26, J34 |
| 2 Five steps | J0, J1, J2, J3, J4, J7, J21, J27, J28 to J30, J31 |
| 3 Moving things | J10, J11, J13, J14, J15, J16, J19, J23, J22 |
| 4 Compress a square | J24, J25, J26, J27 |
| 5 File and player | J28, J28b, J29, J30, J32, J37, J38 |
| 6 Demo | J0, J31, J38, J39, J40, J41, J42, J43 |
| 7 Swap | J34, J49 |
| 8 Lab | J50 |
| 9 Extra credit | J44, J45, J46, J47 |
| 10 Limits | J8, J22, J10 |
