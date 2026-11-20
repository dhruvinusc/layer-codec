# Design decisions

The choices that shaped the codec, mostly where the brief leaves room. Each entry gives the decision, why, and what it costs. Numbers come from `docs/RESULTS.md`. The full day-by-day reasoning, with every option considered, is kept in `docs/history/decision-log/`.

## 1. Java 21, with FFmpeg only to read the mp4
- Decision: all codec code is hand-written Java 21; FFmpeg runs as a separate program that streams upright RGB frames and reports size, frame rate and rotation.
- Why: Java has a window toolkit and zlib built in, so nothing else needs installing, and every graded algorithm stays in our own code.
- Trade-off: tight loops are slower than C++, so real-time 1080p playback needed parallel decoding (decision 13); FFmpeg must be installed to run the encoder.

## 2. Frames are processed upright
- Decision: read the rotation tag and rotate frames explicitly before any processing.
- Why: three of the four clips are stored sideways or upside down, and motion and display must work on the picture as it is seen.
- Trade-off: rotation code that must handle two FFmpeg versions; covered by tests.

## 3. Pad frames to a multiple of 16
- Decision: copy the last row and column outward until both sides are multiples of 16 (1080 becomes 1088), store the true size, and crop on decode.
- Why: 16x16 macroblocks must tile the frame, and copied edge pixels add no false edges or motion.
- Trade-off: the last macroblock row of a 1080p frame is half padding, coded but never shown.

## 4. Motion search on Y with a fast 3-level search
- Decision: match 16x16 blocks on brightness (Y) by sum of absolute differences, searching wide on a quarter-size picture and refining at half and full size; an exact full search is kept as the reference.
- Why: an exact search at +/-16 costs 1.24 s per 1080p frame and video2's camera moves more than 16 px; the fast search takes 190 ms and agrees with the exact one on 89.9% to 95.9% of textured blocks.
- Trade-off: it can miss a match the exact search finds (3.9% of SAL blocks are a real miss).

## 5. A small smoothness penalty in the search
- Decision: when two positions match almost equally well, prefer the vector that agrees with the neighbours.
- Why: repeating bricks and railings match well at several offsets; the penalty stops blocks choosing a vector one brick over.
- Trade-off: a small, flat object can take its neighbours' vector, which is one reason the distant walker on Stairs is missed.

## 6. Background means camera motion, fitted as a homography
- Decision: fit the camera's motion to the trusted vectors with RANSAC (fixed seed, 2.5 px inlier distance), falling back to affine and then a single vector.
- Why: all four clips have a moving camera that pans, tilts and sees perspective; a homography generalises the brief's "one constant vector" and explains 0.737 to 0.902 of trusted blocks on average.
- Trade-off: one flat model cannot fit near and far scenery at once (parallax), so near fences can look like foreground.

## 7. Trust only textured, well-matched blocks
- Decision: a vector counts only if its block has Y variance of at least 100 and a match error (MAD) of at most 12.
- Why: flat blocks such as sky or dark road match anywhere, so their vectors are noise.
- Trade-off: on the night clip video1 most blocks are untrusted and get their label from neighbours.

## 8. Foreground is connected blocks whose leftover motion agrees
- Decision: a candidate block has leftover motion (its vector minus the camera's) over 2.5 px; candidates join a region only if their leftover motion agrees in size or direction; regions under 0.2% of the frame are dropped.
- Why: this is the brief's contiguous and consistent rule, and it removes scattered false matches. On the same vectors it cuts SAL from 8.68% to 1.07% foreground and video2 from 16.56% to 7.25%.
- Trade-off: two people walking side by side join into one region.

## 9. A 2-of-3 frame vote, read along the motion
- Decision: a block is foreground if it is foreground in at least 2 of 3 consecutive frames, looking up the neighbouring frames along the block's motion vector; frame 0 reuses frame 1's labels.
- Why: removes one-frame flickers when the camera shakes, and following the vector matters because the camera can move two blocks per frame.
- Trade-off: an object that appears for a single frame is lost.

## 10. Lean toward foreground when unsure
- Decision: when a choice risks losing a real mover, keep it as foreground. The parallax filter is therefore off by default.
- Why: a person coded with the coarse background step is a visible mistake; extra foreground only costs bytes. The filter would cut video2 from 6.95% to 4.15% foreground but can delete a person moving along the camera's flow.
- Trade-off: some near fence and ground on video2 stays foreground.

## 11. DCT and quantization as in class
- Decision: the orthonormal 8x8 DCT on R, G and B with a level shift of 128; divide by 2^n and round half away from zero, the same step for all 64 coefficients; n from 0 to 10.
- Why: the DCT itself loses nothing (59 dB at n = 0), so rounding is the only loss and is easy to reason about; rounding has about half the error of truncation; above 2^10 every coefficient becomes zero.
- Trade-off: no perceptual weighting of frequencies, which the brief does not ask for.

## 12. A binary .cmp with a header and per-frame zlib
- Decision: keep the brief's content and order (n1, n2, then per 8x8 block its type and 64 R, 64 G, 64 B coefficients) in a binary file with a 48-byte header, each frame packed with zlib, and a frame table at the end.
- Why: the brief's text layout has no size, frame rate or frame count, and as text SAL would exceed 15 GB; zlib is lossless and saves 3x to 9.6x; the frame table allows stepping back. `CmpDump` prints any frame in the brief's text layout.
- Trade-off: the file is not human-readable without the tool, and it is still far larger than the mp4 (424.7 MB against 27.8 MB for SAL).

## 13. Decode whole frames in parallel, show them on a steady clock
- Decision: four workers each decode whole frames into numbered slots; the display shows frame i at start + i / fps and drops a frame that is already a full period late.
- Why: one 1080p frame takes 54.1 ms on one core against a 33.3 ms budget; four workers reach 77 to 82 fps on 1080p and stay identical to serial decoding. 4 workers had fewer late frames than 6 (3.5% against 5.75%).
- Trade-off: about 8 frames of memory are buffered; the first second can have a few late frames while Java warms up.

## 14. Own display scaler and a 1:1 zoom
- Decision: scale frames with our own bilinear code, and offer a 1:1 zoom with panning.
- Why: Java's built-in scaling made SAL play 100% late; our scaler brought it to 0.5% late. Scaling 1080p to fit the screen hides exactly the block artifacts the comparison needs, so the zoom shows them pixel for pixel.
- Trade-off: more code to maintain than a library call.

## 15. Extra credit behind optional flags
- Decision: mode 1 (zigzag and run-length) and mode 2 (motion-compensated, a full frame every 2) are off by default, and a test pins the baseline file bytes.
- Why: the graded baseline never changes. Groups of 2 were chosen after measuring lengths 2 to 8, because longer groups were both larger and worse.
- Trade-off: mode 2 predicts at most every second frame, so it saves 36% to 51% where a real codec would save far more.

## 16. Tune on all four clips together
- Decision: every threshold is one named constant, tuned on all four clips at once and checked by eye on whole clips, never per clip.
- Why: settings tuned to one clip break the others; the same frozen settings also ran unchanged on five extra phone clips.
- Trade-off: no clip gets its individually best result; the distant walker on Stairs is missed in 74 of 345 frames.
