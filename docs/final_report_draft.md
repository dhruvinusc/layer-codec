# Video Layer Based Compression: Final Report (DRAFT)

CSCI 576, Professor Parag Havaldar. Dhruvin Jhaveri. Draft of Nov 14, 2026.

Every number below carries its source in brackets: `RESULTS "section"` is a section of `docs/RESULTS.md`, `p1_report` is `docs/p1_report.md`, `p3_report` is `docs/p3_report_draft.md`, and `P1-L13` style ids are entries in `docs/decision-log/`. Mac means the MacBook Air (8 cores, fanless) the project runs on; sandbox means the Linux build environment, used for correctness and sizes but not for official timings.

Items still open when this draft was written are marked **(pending)**.

---

## 1. The problem

The brief asks for a two-layer video codec. The encoder splits each frame into moving foreground objects and background, using motion vectors only, and then compresses foreground blocks finely and background blocks coarsely. The command is `myencoder input_video.mp4 n1 n2`, where every quantization step is a power of two: 2^n1 for foreground and 2^n2 for background. The decoder, `mydecoder input_video.cmp`, needs only the compressed file, plays it at the original frame rate, and supports pause and frame stepping. When n1 < n2 the foreground must look visibly cleaner than the background, and the effect must reverse when the two are swapped.

The four test clips all have a moving camera: a handheld night street crossing (video1), a fast tracking shot of two joggers (video2), a pan and tilt down a staircase (Stairs), and a smooth pan along a brick wall (SAL). Two are 1080p, two are 480x848 portrait, and three carry rotation metadata.

## 2. Pipeline overview

```
mp4 -> upright RGB frames (FFmpeg) -> pad to multiples of 16
    -> Y plane -> motion vector per 16x16 block (vs previous frame)
    -> camera motion (homography) -> leftover motion per block
    -> foreground / background label per 16x16 block
    -> each 8x8 block inherits its label -> 8x8 DCT on R, G, B
    -> quantize with step 2^n1 (foreground) or 2^n2 (background)
    -> .cmp file (header, per-frame payloads, zlib, frame table)
.cmp -> parse -> dequantize by block type -> inverse DCT -> crop -> play at the source frame rate
```

### 2.1 Implementation notes

The codec is written in Java 21. The launchers `myencoder` and `mydecoder` (and `myencoder.bat`, `mydecoder.bat` on Windows) take exactly the brief's arguments; the only addition is an optional `--mode rle` for the encoder, off by default (section 8). FFmpeg, with ffprobe, is used only to read the input mp4: it is run as a separate program that streams upright RGB frames and reports size, frame rate and rotation. Everything the brief grades is hand-written: motion estimation, camera-motion fitting, segmentation, the 8x8 DCT and its inverse, quantization, the .cmp file format and its reader and writer, display scaling and playback timing. The Java standard library provides the window and keyboard handling (Swing), threads, and zlib (`java.util.zip`) for lossless packing of each frame.

## 3. Segmentation (encoder, part 1)

### 3.1 Method
1. **Motion vectors.** Each 16x16 block of the Y plane is matched against the previous frame by sum of absolute differences (equivalent to MAD for choosing the best match). The default is a 3-level hierarchical search (quarter, half and full size) with a small smoothness penalty, so a 30 pixel move costs little and repeating textures such as bricks prefer the vector that agrees with the neighbours. A full search is kept as the reference. [P1-L5 to P1-L8]
2. **Trusted blocks.** A vector is trusted for the camera fit only if the block has enough texture (Y variance at least 100) and a good match (MAD at most 12). [P1-L9]
3. **Camera motion.** All four clips have a moving camera, so "background" means "whatever motion the camera causes". A homography is fitted to the trusted vectors with RANSAC (500 samples, fixed seed, 2.5 px inlier distance), falling back to affine and then a single vector. This generalises the brief's "one constant motion vector" to a camera that also rotates, tilts and sees perspective. [P1-L11]
4. **Candidates.** A block is a foreground candidate if its leftover motion (its vector minus the camera's vector at that block) is longer than 2.5 px, it has a good match and some texture, and the camera's own vector does not match it almost as well. The last test removes patches of repeating brick that lock onto a vector one brick over. [P1-L13, P1-L15, P1-L19]
5. **Grouping (the brief's contiguity and consistency rule).** Candidates grow into 4-connected regions, but a neighbour joins only if its leftover motion agrees in size or direction. Regions smaller than 0.2% of the frame, or whose motion is too scattered (median spread over 3 px), are dropped. [P1-L13, P1-L14]
6. **Fill.** A block surrounded on 3 of 4 sides by one region joins it, and holes enclosed by one region are filled. Growing every region by one block was tried and rejected because it doubled foreground on every clip. [P1-L17]
7. **Temporal vote.** A block is foreground if it is foreground in at least 2 of 3 consecutive frames. The neighbouring frames are read along each block's motion vector, because the camera moves up to two blocks per frame on video2. Frame 0 reuses frame 1's labels. [P1-L18]

Every threshold was tuned on all four clips together, never per clip, and every candidate setting was checked by eye on whole clips. One setting that looked best by the numbers was rejected because it erased the joggers and a skateboarder. [P1-L19]

### 3.2 Naive versus final

The naive baseline uses the same vectors and camera model with the simplest rule: a textured block with leftover motion over 3 px, in groups of at least 4 blocks. [P1-L12]

| Clip | Naive foreground, 3 sample frames (sandbox) [RESULTS "P1.5 naive baseline overlays"] | Final foreground, whole clip mean (sandbox) [RESULTS "P1.10 final labels and determinism"] |
|---|---|---|
| video1 | 2.4%, 7.6%, 3.8% | 2.70% |
| video2 | 23.5%, 24.8%, 6.3% | 6.95% |
| Stairs | 2.4%, 5.5%, 8.6% | 1.51% |
| SAL | 12.1%, 11.1%, 3.8% | 1.07% |

In the naive result the walker is found but the SAL brick wall and the video2 fence light up. In the final result the SAL wall is clean and the walker is found in all three sample frames. Images: `docs/img/naive_*.jpg` and `docs/img/p1_final_*.jpg`. [p1_report]

Supporting numbers:
- The camera homography was accepted on 1679 of 1680 frame pairs and explains 74% to 90% of trusted blocks on average. [RESULTS "P1.5 camera motion"]
- The fast search agrees with the exact search on 89.9% (SAL) to 95.9% (video2) of textured blocks on the Mac. Real misses are 1.6% to 4.0%; most SAL disagreements are equally good matches on repeating bricks. [RESULTS "W2 / D13 Mac numbers", P1-L10]
- Labels are deterministic: the same input twice on one machine gives byte-identical labels files (SHA-256). A different FFmpeg build decodes slightly different pixels, so Mac and sandbox labels differ by 0.11% of blocks; the Mac is the reference. [RESULTS "P1.10 final labels and determinism", RESULTS "W2 / D13 Mac numbers"]

## 4. Compression and the .cmp format (encoder, part 2)

- **Blocks.** The padded frame is split into 8x8 blocks in scan line order. Each block inherits the label of its 16x16 macroblock. On the four real encodes, every block type in the file equals its macroblock's label, with 0 mismatches over 34.1 million blocks. [RESULTS "W2 real encodes"]
- **DCT.** The orthonormal 8x8 DCT used in class, on R, G and B, with the level shifted by 128 before the forward transform. The DCT itself loses nothing: at n = 0 only rounding is lost (59 dB). [RESULTS "P2.9 Mac measurements", P2-L1]
- **Quantization.** Every coefficient is divided by 2^n and rounded half away from zero; the decoder multiplies back. The same step is used for all 64 coefficients, as the brief asks. [P2-L4]
- **File format.** A binary file instead of the brief's suggested text layout, with the same logical content and order. A 48-byte header carries the magic `LYRC`, version, flags, true and padded size, frame rate, frame count, n1 and n2. Each frame then stores its block types and 64 x 3 coefficients per block as 16-bit integers, compressed with zlib, and a frame table at the end allows stepping back and seeking. The brief's text layout has no place for size, frame rate or frame count, and as text the SAL clip would be far larger. A dump tool prints any frame in the brief's exact text layout. [P2-L5 to P2-L9, P2-L14]
- **Size.** zlib is lossless and saves 3x to 9.6x depending on n and foreground share. [RESULTS "P2.9 Mac measurements"] The .cmp is still much larger than the mp4, because the baseline stores every frame on its own, with no prediction between frames and no entropy coding beyond zlib.

## 5. Decoder and player (part 3)

- **Decoding.** The decoder reads only the .cmp. It dequantizes each block with n1 or n2 according to its block type, inverts the DCT, crops the padding and shows the frame. [p3_report]
- **Real-time playback.** One 1080p frame takes 54.1 ms to decode on one core at (2,5), against a 33.3 ms budget at 30 fps. So several whole frames are decoded at once on worker threads and shown in order on a steady clock. That reaches 60.7 fps at (2,5) and 47.7 fps at (0,0), bit-identical to serial decoding. [RESULTS "P3.1 DecodePipeline bench"]
- **Late frames.** If a frame is late it is dropped to hold the original rate, and dropped and late frames are counted on screen. [P3-L7]
- **Controls.** Space plays and pauses, the arrows step forward and back (the last 30 frames are cached), `s` shows the decoded and original video side by side, `z` zooms to 1:1 so block artifacts are not hidden by scaling, and `d` tints the foreground blocks using the block types in the file. [p3_report, P3-L6, P3-L11, P3-L13]

## 6. Results

### 6.1 Quality per layer (PSNR)

Real labels, all four clips at (n1, n2) = (2, 5), sandbox (PSNR does not depend on the machine): [RESULTS "W2 real encodes"]

| Clip | PSNR all | Foreground | Background |
|---|---|---|---|
| video1 | 37.73 dB | 47.98 dB | 37.62 dB |
| video2 | 34.29 dB | 47.78 dB | 33.99 dB |
| Stairs | 35.30 dB | 48.12 dB | 35.24 dB |
| SAL | 34.38 dB | 47.53 dB | 34.34 dB |

Swapping n1 and n2, measured with test labels (half the blocks foreground) on the Mac: [RESULTS "P2.9 Mac measurements"]

| Clip | (2,5) foreground / background | (5,2) foreground / background |
|---|---|---|
| video2 (120 frames) | 48.14 / 34.39 dB | 34.40 / 48.14 dB |
| SAL (60 frames) | 47.74 / 34.48 dB | 34.48 / 47.74 dB |

**(pending)** The swap with real labels is part of P3.R and will replace the second table. Side-by-side images at (2,5) and (5,2): `docs/img/p3_SAL_n2_5_side_1to1.png`, `docs/img/p3_SAL_n5_2_side_1to1.png`. [p3_report]

### 6.2 File sizes at (2,5)

Sizes from the sandbox encodes; the Mac SAL file is 424.7 MB because of a different FFmpeg and zlib build. [RESULTS "W2 real encodes", RESULTS "W2 / D13 Mac numbers"]

| Clip | Frames | Input mp4 | .cmp | Foreground 8x8 blocks |
|---|---|---|---|---|
| video1 | 518 | 3.6 MB | 65.9 MB | 2.70% |
| video2 | 383 | 2.7 MB | 86.7 MB | 6.94% |
| Stairs | 346 | 21.9 MB | 294.6 MB | 1.50% |
| SAL | 437 | 27.8 MB | 431.0 MB | 1.07% |

### 6.3 Timings on the Mac

| Check | Result | Target | Source |
|---|---|---|---|
| Encode full SAL, `./myencoder data/SAL.mp4 2 5` | 1 min 51 s | encode + decode under 10 min | RESULTS "W2 / D13 Mac numbers" |
| Motion search per 1080p frame (hierarchical) | 190 ms | 200 ms | RESULTS "W2 / D13 Mac numbers" |
| Segmentation, whole SAL | 90.7 s | 3 min | RESULTS "W2 / D13 Mac numbers" |
| Play SAL, real file | 0 dropped, 4.3% late (mostly warm-up), +0.02% duration | real time | RESULTS "W2 / D13 Mac numbers" |
| Play video1 / video2, real files | 0 dropped, 0 and 1 late | real time | RESULTS "W2 / D13 Mac numbers" |
| Play full SAL, test file, (2,5) and (0,0) | 0 dropped; first and last 100 frames 29.9 vs 30.0 fps | no thermal slowdown | RESULTS "Quiet run" |

**(pending)** Stairs playback on the real file was not measured cleanly (both runs ended early). It is part of P3.R, together with the decode half of the 10-minute check (E4).

## 7. Limits

- **Distant, slow walker on Stairs.** 79 of 346 Stairs frames have no foreground, mostly at the start while the walker is far away. [RESULTS "P1.10 final labels and determinism"]
  - **Why:** he moves about 2 px per frame against the camera, which is near the 0.7 px error of the camera model and the whole-pixel precision of block matching. His dark, flat jacket also takes the camera's vector through the search's smoothness penalty.
  - **What was tried:** a lower threshold, an exact camera test, an adaptive threshold, and adding up motion over several frames. All four were measured and none kept: each found little or nothing new, and the multi-frame version raised video2's false foreground from 6.95% to between 7.6% and 9.8%. [RESULTS "P1.6 known limit", RESULTS "W3 accumulated residual for slow movers", P1-L16, P1-L21]
  - **Effect:** in those frames he is compressed with the background step.
- **Parallax on video2.** The camera runs alongside the joggers, so the fence and trees close to the camera slide faster than the far background, and a flat camera model labels them foreground.
  - **Filter built:** it drops regions whose leftover motion points along the camera's flow, and cuts video2's foreground from 6.95% to 4.15%. [RESULTS "P1.9 step 2"]
  - **Kept off by default:** it also deletes a real person walking against the camera's direction, and project decision D3 is to lean foreground when unsure, because a blurred person is the mistake a viewer notices. The comparison is `VizSeg --parallax 30`; images `docs/img/p1_parallax_video2_off.jpg` and `_on.jpg`. [P1-L20]
- **Uncovered background.** When a moving object reveals background it was hiding, the revealed blocks have no good match in the previous frame, so their vectors are unreliable and can show up as small foreground fragments at the object's trailing edge. This was not measured separately; the match-quality gate and the 2-of-3 frame vote reduce it.
- **Articulated motion.** The joggers' arms and legs move in mixed directions, so the consistency rule finds them only in part. [p1_report]
- **Cross-machine labels.** Different FFmpeg builds decode slightly different pixels, so labels can differ by about 0.1% of blocks between machines. [RESULTS "W2 / D13 Mac numbers"]

## 8. Extra credit

### 8.1 Zigzag and run-length coefficients (done, .cmp mode 1)
- **What it does:** `./myencoder input.mp4 n1 n2 --mode rle` writes the same quantized coefficients in zigzag order with run-length coding. The baseline remains the default, and its bytes are pinned by a golden test. [P4-L2]
- **Size:** with test labels, files are 1.18x (video2) to 1.23x (SAL) smaller. [RESULTS "P4-B zigzag + run-length"]
- **Speed:** decoding is faster, 76.2 vs 61.0 fps for a 4-worker pipeline on SAL, with pixel-identical pictures. [RESULTS "Quiet run"]
- **(pending)** sizes with real labels.

### 8.2 Motion-compensated residual coding (placeholder)
**(pending)** Approved, to be built after P3.R; it ships only if solid by Nov 24. This section will describe residual coding with one motion vector per macroblock, prediction from the reconstructed previous frame, the I-frame interval, and size and PSNR per layer against the baseline.

## 9. How to build and run

Requirements: Java 21 and FFmpeg (with ffprobe) on the PATH. On the project Mac, FFmpeg comes from conda, so activate conda base first or set the `FFMPEG` and `FFPROBE` environment variables.

```
cd layer-codec
./gradlew build                         # compiles and runs all tests (219 at the time of writing)
./myencoder data/SAL.mp4 2 5            # writes data/SAL.cmp
./mydecoder data/SAL.cmp                # plays at the source frame rate
./mydecoder data/SAL.cmp data/SAL.mp4   # same, with the original available for side by side
```

Player keys: space play/pause, right arrow step forward, left arrow step back, `s` side by side, `z` 1:1 zoom (drag to pan), `d` foreground overlay, `q` quit.

Optional, off by default: `./myencoder input.mp4 n1 n2 --mode rle` (extra credit mode 1). Windows users run `myencoder.bat` and `mydecoder.bat` with the same arguments.

Inspection tools (not needed to grade):
- `java -cp build/libs/layer-codec.jar codec.tools.VizSeg <clip> --stats --labels --sheet out.jpg` draws the segmentation overlays and writes the labels file.
- `codec.tools.CmpDump` prints a frame in the brief's text layout.
- `codec.tools.Psnr <mp4> <cmp>` measures PSNR per layer.

## 10. Tools used

- **Java 21** (Temurin JDK) for all code, built with the **Gradle** wrapper; **JUnit 5** for the 219 automated tests, including slow textbook versions of the DCT and the motion search that check the fast versions.
- **FFmpeg / ffprobe** only to decode the input mp4 into RGB frames and read its metadata. No FFmpeg filters or encoders are used for compression, segmentation or display.
- **AI assistance** was used for planning the work, reviewing code, and drafting documents such as this report and the decision logs. Every design choice was measured, written down with its reasons and alternatives in `docs/decision-log/`, and is understood and defended by me.

