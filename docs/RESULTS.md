# Results

Measured on the project machine: a MacBook Air (Apple Silicon, 8 cores, fanless, macOS 14.2.1), Temurin JDK 21, FFmpeg 7.1. Times come from the real launchers after a warm-up run. A few numbers come from the Linux build environment (4 cores, FFmpeg 4.4); they are marked "build env" and are used only for results that do not depend on the machine, such as sizes, counts and label checks.

PSNR is the mean over frames, in dB, written as all pixels / foreground / background.

## Segmentation

Share of 16x16 blocks labelled foreground over the whole clip. Frame 0 is left out because it reuses frame 1's labels. "Naive" uses the same motion vectors and camera model with the simplest rule (leftover motion over 3 px, groups of at least 4 blocks), so only the segmentation rules differ.

| Clip | Frames analysed | Naive | Final | Frames with no foreground |
|---|---|---|---|---|
| video1 | 517 | not run | 2.66% | 19 |
| video2 | 382 | 16.56% | 7.25% | not recorded |
| Stairs | 345 | not run | 1.57% | 74 |
| SAL | 436 | 8.68% | 1.07% | 10 |

The final rules remove most false foreground on the SAL brick wall and the video2 fence while keeping the walkers. Images: `docs/img/naive_*.jpg` and `docs/img/p1_final_*.jpg`.

Motion search: the fast 3-level search against the exact full search. "Agree" counts textured blocks where both vector parts are within 1 px. "Worse" counts blocks where the fast match error is more than 10% higher, which is a real miss rather than an equally good alternative.

| Clip | Full search range | Fast, ms per frame | Full, ms per frame | Agree | Worse |
|---|---|---|---|---|---|
| SAL (1080p) | +/-16 | 190 | 386 | 89.9% | 3.9% |
| video2 | +/-32 | 37 | 227 | 95.9% | 1.6% |
| video1 (build env) | +/-16 | not timed | not timed | 91.7% | 4.0% |
| Stairs (build env) | +/-16 | not timed | not timed | 91.8% | 3.0% |

Most SAL disagreements are equally good matches on the repeating bricks. An exact search at +/-16 would cost 1.24 s per 1080p frame. Motion search plus segmentation of the whole SAL clip takes 90.7 s.

Camera motion (build env, every frame pair): RANSAC homography with a 2.5 px inlier distance, falling back to affine, then translation.

| Clip | Frame pairs | Model used | Mean inlier share | Mean inlier error |
|---|---|---|---|---|
| video1 | 517 | homography 100% | 0.770 | 1.07 px |
| video2 | 382 | homography 381, translation 1 | 0.737 | 1.07 px |
| Stairs | 345 | homography 100% | 0.902 | 0.72 px |
| SAL | 436 | homography 100% | 0.853 | 0.61 px |

Labels are deterministic: video2 run twice gives byte-identical labels (SHA-256 `f568f6ac25924709...`). The Mac and the build env use different FFmpeg versions, which decode slightly different pixels, so 0.11% of SAL's blocks (3,871 of 3.57 M) get a different label across machines. The optional parallax filter, off by default, would cut video2 from 6.95% to 4.15% foreground (build env), but it can delete a real person moving along the camera's flow.

## Compression

Layer effect with test labels (a checkerboard: half the macroblocks foreground), zlib level 1. video2: first 120 frames; SAL: first 60 frames.

| Clip | n1, n2 | Stored MB per frame | zlib ratio | PSNR all / fg / bg |
|---|---|---|---|---|
| video2 | 0, 0 | 0.81 | 3.03x | 59.47 / 59.47 / 59.47 |
| video2 | 2, 5 | 0.35 | 6.99x | 37.22 / 48.14 / 34.39 |
| video2 | 5, 2 | 0.35 | 7.00x | 37.23 / 34.40 / 48.14 |
| video2 | 1, 7 | 0.38 | 6.37x | 29.72 / 52.76 / 26.73 |
| video2 | 4, 4 | 0.26 | 9.38x | 38.80 / 38.80 / 38.79 |
| SAL | 0, 0 | 4.21 | 2.98x | 58.97 / 58.97 / 58.97 |
| SAL | 2, 5 | 1.79 | 7.00x | 37.29 / 47.74 / 34.48 |
| SAL | 5, 2 | 1.79 | 7.03x | 37.29 / 34.48 / 47.74 |
| SAL | 1, 7 | 2.01 | 6.26x | 29.81 / 52.21 / 26.81 |
| SAL | 4, 4 | 1.31 | 9.59x | 38.79 / 38.80 / 38.79 |

At n = 0 only rounding is lost (59 dB), so the DCT itself loses nothing. A larger n lowers both PSNR and size, and swapping n1 and n2 swaps the layers. zlib is lossless and saves 3x to 9.6x; without it a 1080p frame is 12.57 MB.

Swap with real labels, SAL:

| SAL, real labels | .cmp size | PSNR all / fg / bg | Encode |
|---|---|---|---|
| (2,5) | 424.7 MB | 34.38 / 47.52 / 34.34 | 115 s |
| (5,2) | 1142.8 MB | 46.87 / 34.40 / 47.72 | 130 s |

The cleaner layer follows n1 and n2. The (5,2) file is larger because 99% of SAL's blocks are background and now get the fine step. Image: `docs/img/p3r_SAL_swap_crop_x3.png` (original, (2,5), (5,2)).

Block types (build env, (2,5)): every 8x8 block type in the .cmp equals the label of its 16x16 macroblock.

| Clip | 8x8 blocks checked | Mismatches | Foreground share |
|---|---|---|---|
| video1 | 3,294,480 | 0 | 2.70% |
| video2 | 2,435,880 | 0 | 6.94% |
| Stairs | 11,293,440 | 0 | 1.50% |
| SAL | 14,263,680 | 0 | 1.07% |

0 mismatches over 34.1 million blocks.

## Decoder and playback

Decode speed on the real (2,5) files.

| Clip | One core, ms per frame | 4 workers, fps | Times real time |
|---|---|---|---|
| video1 (480x848) | 7.2 | 463.3 | 15.4x |
| video2 (480x848) | 8.8 | 407.6 | 13.6x |
| Stairs (1080p) | 40.4 | 82.9 | 2.8x |
| SAL (1080p) | 42.4 | 81.0 | 2.7x |

At 30 fps each frame has 33.3 ms. On test files a 1080p frame takes 54.1 ms on one core at (2,5) and 71.9 ms at (0,0), so four workers decode whole frames in parallel and a steady clock shows them in order. The parallel output is identical to serial decoding.

Full-length SAL through the real player, 4 workers, on battery:

| File | Dropped | Late | Played vs clip | First 100 frames | Last 100 frames |
|---|---|---|---|---|---|
| SAL (2,5) | 0 | 2 (0.5%) | 14.58 vs 14.57 s | 29.9 fps | 30.0 fps |
| SAL (0,0) | 0 | 34 (7.8%) | 14.58 vs 14.57 s | 29.9 fps | 30.0 fps |

The last 100 frames run as well as the first 100, so the fanless laptop does not slow down over a full clip.

Display scaling, SAL 437 frames:

| Scaler | Late frames | Duration error |
|---|---|---|
| Java2D built-in scaling | 437 (100%) | +43.0% |
| Own bilinear scaler, run 1 | 2 (0.5%) | -0.1% |
| Own bilinear scaler, run 2 | 6 (1.4%) | -0.2% |

Worker count: on 200 SAL frames, 4 workers averaged 3.5% late frames and 6 workers 5.75%, both with 0 dropped. 4 is the default.

## Extra credit

Both modes are optional and off by default; the baseline file bytes are pinned by a test. Mode 1 stores the same coefficients in zigzag order with run-length coding. Mode 2 predicts every second frame from the previous decoded frame with one motion vector per macroblock and codes only the difference. Full per-clip numbers are in Final check.

| Clip | Mode 1, smaller by | Mode 1 pixels | Mode 2, smaller by |
|---|---|---|---|
| video1 | 40% | same as baseline | 50% |
| video2 | 29% | same as baseline | 36% |
| Stairs | 33% | same as baseline | 48% |
| SAL | 37% | same as baseline | 51% |

Choosing the group length for mode 2 (build env, video2 first 96 frames, (2,5)):

| Group length | Size | PSNR all / fg / bg |
|---|---|---|
| baseline | 20.2 MB | 34.28 |
| 2 | 13.2 MB | 34.24 / 47.46 / 33.97 |
| 3 | 13.0 MB | 33.99 / 47.33 / 33.72 |
| 4 | 13.4 MB | 33.75 / 47.23 / 33.48 |
| 6 | 13.7 MB | 33.39 / 47.11 / 33.11 |
| 8 | 14.2 MB | 33.12 / 47.04 / 32.84 |

Longer groups were not smaller and were worse: with steps of 2^n, small changes round to zero on each predicted frame, so the error grows until the next full frame. Groups of 2 keep baseline quality.

## Uneven bits

Measured after the code freeze with tools that call the frozen encoder classes. Their byte counts equal the real encoder's files (video2 at (2,5): 85.4, 60.9 and 54.5 MB in the three modes).

| Test | Result |
|---|---|
| video2, n1 fixed at 2, n2 raised | foreground stays at 47.7 dB while the file falls from 93.9 to 13.7 MB |
| video2, (2,6) against (4,4) | the subject gets 9.2 dB more for 41% fewer bytes |
| SAL at a 100 Mbps budget | same foreground in every mode; background 23.2 dB in baseline, 30.1 dB in mode 2 |

The background is nearly all of the blocks, so n2 sets the file size and n1 sets the subject's quality. Images: `docs/img/rd_video2_even_vs_uneven.png`, `docs/img/rd_video2_fixed_n1.png`, `docs/img/sizes_4clips.png`, `docs/img/dct_SAL_f150.png`.

## Field clips

Five phone clips (1080x1920 portrait), run through the frozen code with default settings. They are extra tests, not part of the official results.

| Clip | Scene | Frames | fps |
|---|---|---|---|
| cars_still | traffic, camera still | 192 | 30 |
| cars_both_ways | traffic in both directions, camera still | 269 | 30 |
| shoppers | many people indoors | 617 | 30 |
| scooter_followed | scooter rider, camera follows | 541 | 29.97 |
| scooter_fast | fast scooter rider | 164 | 30 |

All five played with 0 dropped frames and within 0.17% of the clip length, and encoding took 40 to 149 s per clip. The 29.97 fps clip kept its exact rate because the header stores the rate as a fraction.

## Final check

Run at the submitted code (commit 88fc181, `bash tools/final_check.sh`, on battery). `./gradlew build`: 234 tests passed, including the test that pins the baseline file bytes.

| Clip | Baseline: size, PSNR all / fg / bg, encode, decode fps | Mode 1: size, encode, decode fps | Mode 2: size, PSNR all / fg / bg, encode, decode fps |
|---|---|---|---|
| video1 | 65.0 MB, 37.72 / 48.01 / 37.62, 25 s, 323 fps | 38.7 MB, 25 s, 548 fps | 32.4 MB, 37.49 / 47.77 / 37.39, 21 s, 448 fps |
| video2 | 85.4 MB, 34.30 / 47.79 / 33.98, 19 s, 395 fps | 60.9 MB, 19 s, 495 fps | 54.5 MB, 34.08 / 47.56 / 33.75, 16 s, 408 fps |
| Stairs | 291.0 MB, 35.30 / 48.13 / 35.24, 85 s, 81.5 fps | 193.9 MB, 86 s, 105.1 fps | 151.1 MB, 35.11 / 47.84 / 35.04, 75 s, 87.9 fps |
| SAL | 424.7 MB, 34.38 / 47.52 / 34.34, 115 s, 77.2 fps | 269.0 MB, 113 s, 95.0 fps | 207.3 MB, 34.24 / 47.31 / 34.20, 112 s, 78.9 fps |

Input mp4 sizes: video1 3.6 MB, video2 2.7 MB, Stairs 21.9 MB, SAL 27.8 MB. The baseline .cmp is much larger because it stores every frame on its own.

- All 12 files played with the plain `./mydecoder`: 0 dropped frames in every run, and durations within 0.10% of the clip length.
- Time limit: the worst encode is SAL at (5,2), 130 s, and playback runs in real time (14.58 s for the 14.57 s clip), well under 10 minutes.
- Sizes measured on the Mac and in the build env agree within 1 point, and PSNR within 0.02 dB, for the same labels.
