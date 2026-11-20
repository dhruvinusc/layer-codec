# Video Layer Based Compression: Final Report

CSCI 576, Professor Parag Havaldar. Dhruvin Jhaveri. December 2, 2026.

All timings and sizes are measured on the project machine, a MacBook Air (8 cores, fanless, JDK 21, FFmpeg 7.1), unless marked otherwise. Bracketed references point to the evidence in the repository: `RESULTS "section"` is a section of `docs/RESULTS.md`, and ids such as `P1-L13` are entries in `docs/decision-log/`, where every design choice is written down with the options considered and the reason.

---

## 1. The problem

The brief asks for a two-layer video codec. The encoder splits each frame into moving foreground objects and background, using motion vectors only, and then compresses foreground blocks finely and background blocks coarsely. The command is `myencoder input_video.mp4 n1 n2`, where every quantization step is a power of two: 2^n1 for foreground and 2^n2 for background. The decoder, `mydecoder input_video.cmp`, needs only the compressed file, plays it at the original frame rate, and supports pause and frame stepping. When n1 < n2 the foreground must look visibly cleaner than the background, and the effect must reverse when the two are swapped.

The four test clips all have a moving camera: a handheld night street crossing (video1), a fast tracking shot of two joggers (video2), a pan and tilt down a staircase (Stairs), and a smooth pan along a brick wall (SAL). Two are 1080p, two are 480x848 portrait, and three are stored rotated with a rotation tag.

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

### 2.1 Implementation

The codec is written in Java 21. The launchers `myencoder` and `mydecoder` (and `myencoder.bat`, `mydecoder.bat` on Windows) take exactly the brief's arguments. FFmpeg, with ffprobe, is used only to read the input mp4: it runs as a separate program that streams upright RGB frames and reports size, frame rate and rotation. Everything the brief grades is hand-written: motion estimation, camera-motion fitting, segmentation, the 8x8 DCT and its inverse, quantization, the .cmp format with its reader and writer, display scaling and playback timing. The Java standard library provides the window and keyboard (Swing), threads, and zlib (`java.util.zip`) for lossless packing of each frame.

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

| Clip | Naive, whole clip | Final, whole clip | Frames with no foreground (final) |
|---|---|---|---|
| video1 | not run | 2.66% | 19 of 517 |
| video2 | 16.56% | 7.25% | not recorded |
| Stairs | not run | 1.57% | 74 of 345 |
| SAL | 8.68% | 1.07% | 10 of 436 |

Share of 16x16 blocks labelled foreground; frame 0 excluded because it copies frame 1. [RESULTS "Mac re-measure (Nov 16)"]

On the same vectors and camera model, the final rules cut SAL from 8.68% to 1.07% of blocks and video2 from 16.56% to 7.25%. In the naive result the walker is found but the SAL brick wall and the video2 fence light up; in the final result the SAL wall is clean and the walker is kept. Images: `docs/img/naive_*.jpg` and `docs/img/p1_final_*.jpg`.

Supporting numbers:
- The camera homography was accepted on 1679 of 1680 frame pairs and explains 74% to 90% of trusted blocks on average. [RESULTS "P1.5 camera motion"]
- The fast search agrees with the exact search within 1 px on 89.9% (SAL) to 95.9% (video2) of textured blocks. Real misses are 1.6% to 4.0%; most SAL disagreements are equally good matches on repeating bricks. [RESULTS "W2 / D13 Mac numbers", P1-L10]
- Motion search takes 190 ms per 1080p frame; segmentation of the whole SAL clip takes 90.7 s. [RESULTS "W2 / D13 Mac numbers"]
- Labels are deterministic: the same input twice gives byte-identical labels (SHA-256). A different FFmpeg build decodes slightly different pixels, so labels from another machine can differ by about 0.1% of blocks. [RESULTS "P1.10 final labels and determinism"]

## 4. Compression and the .cmp format (encoder, part 2)

- **Blocks.** The padded frame is split into 8x8 blocks in scan line order. Each block inherits the label of its 16x16 macroblock; on the four real encodes every block type equals its macroblock's label, with 0 mismatches over 34.1 million blocks. [RESULTS "C1 label check method"]
- **DCT.** The orthonormal 8x8 DCT used in class, on R, G and B, with the level shifted by 128 before the forward transform. The DCT itself loses nothing: at n = 0 only rounding is lost (59 dB). [RESULTS "P2.9 Mac measurements", P2-L1]
- **Quantization.** Every coefficient is divided by 2^n and rounded half away from zero; the decoder multiplies back. The same step is used for all 64 coefficients, as the brief asks. This is the only lossy step. [P2-L4]
- **File format.** A binary file instead of the brief's suggested text layout, with the same logical content and order. A 48-byte header carries the magic `LYRC`, version, flags, true and padded size, frame rate (as an exact fraction), frame count, n1 and n2. Each frame then stores, per 8x8 block, its block type and 64 R, 64 G and 64 B coefficients as 16-bit integers, compressed with zlib, and a frame table at the end allows stepping back and seeking. The brief's text layout has no place for size, frame rate or frame count, and as text the SAL clip would exceed 15 GB. The tool `CmpDump` prints any frame in the brief's exact text layout. [P2-L5 to P2-L9, P2-L14]
- **Size.** zlib is lossless and saves 3x to 9.6x depending on n and foreground share. [RESULTS "P2.9 Mac measurements"] The .cmp is still much larger than the mp4, because the baseline stores every frame on its own, with no prediction between frames and no entropy coding beyond zlib.

## 5. Decoder and player (part 3)

- **Decoding.** The decoder reads only the .cmp. It dequantizes each block with n1 or n2 according to its block type, inverts the DCT, crops the padding and shows the frame.
- **Real-time playback.** One 1080p frame takes 54.1 ms to decode on one core at (2,5), against a 33.3 ms budget at 30 fps. So whole frames are decoded on four worker threads at once and shown in order on a steady clock (frame i is due at start + i / fps). This reaches 77 to 82 fps on the 1080p clips, bit-identical to serial decoding. [RESULTS "Final Mac check", RESULTS "P3.1 DecodePipeline bench"]
- **Late frames.** A frame that would be late is dropped to hold the original rate; dropped and late frames are counted on screen. [P3-L7]
- **Controls.** Space plays and pauses, the arrows step forward and back (the last 30 frames are cached, older ones are decoded again through the frame table), `s` shows the decoded and original video side by side (the original mp4 is an optional second argument), `z` zooms to 1:1 so block artifacts are not hidden by scaling, and `d` tints the foreground blocks using the block types in the file. [P3-L6, P3-L11, P3-L13]

## 6. Results

All rows below come from the final check on the Mac at the submitted code (commit 88fc181, 234 automated tests passing). [RESULTS "Final Mac check"]

### 6.1 Quality per layer and size at (n1, n2) = (2, 5)

PSNR is the mean over frames, in dB: all pixels / foreground / background.

| Clip (frames) | Input mp4 | .cmp | PSNR all / fg / bg | Encode | Decode (4 workers) |
|---|---|---|---|---|---|
| video1 (518) | 3.6 MB | 65.0 MB | 37.72 / 48.01 / 37.62 | 25 s | 323 fps |
| video2 (383) | 2.7 MB | 85.4 MB | 34.30 / 47.79 / 33.98 | 19 s | 395 fps |
| Stairs (346, 1080p) | 21.9 MB | 291.0 MB | 35.30 / 48.13 / 35.24 | 85 s | 81.5 fps |
| SAL (437, 1080p) | 27.8 MB | 424.7 MB | 34.38 / 47.52 / 34.34 | 115 s | 77.2 fps |

The foreground is 10 to 14 dB cleaner than the background on every clip. The overall PSNR sits close to the background value because the background is 93% to 99% of the blocks.

### 6.2 Swapping n1 and n2

| SAL, real labels | Foreground | Background | File |
|---|---|---|---|
| (2,5) | 47.52 dB | 34.34 dB | 424.7 MB |
| (5,2) | 34.40 dB | 47.72 dB | 1142.8 MB |

The cleaner layer swaps exactly as the brief requires. The (5,2) file is 2.7 times larger because the background, about 99% of the blocks, now gets the fine step. Image: `docs/img/p3r_SAL_swap_crop_x3.png` (original, (2,5), (5,2) at 1:1, enlarged 3x). [RESULTS "Final Mac check"]

### 6.3 Playback and time limit

- All 12 files (4 clips x 3 modes, section 9) played with the plain `./mydecoder`: 0 dropped frames in every run, and playback duration within 0.10% of the clip length. [RESULTS "Final Mac check"]
- Full-length SAL played at (2,5) and (0,0) on battery with no thermal slowdown: 29.9 fps over the first 100 frames and 30.0 fps over the last 100. [RESULTS "Quiet run"]
- Encode plus decode: the worst case is SAL at (5,2), 130 s to encode plus 14.6 s of real-time playback, well under the 10 minute limit. [RESULTS "Final Mac check", RESULTS "E4"]

## 7. Uneven bits for streaming

The professor framed the goal as "distributing compression bits unevenly between foreground and background for better compression efficiency for streaming". After the code was frozen, a separate lab folder measured this directly with tools that call the frozen encoder classes without changing them. Its byte counts equal the real encoder's files exactly (video2 at (2,5): 85.4, 60.9 and 54.5 MB in the three modes). [lab/rd/RESULTS_L3.md]

- **Same size, different split.** On video2 (first 120 frames), the even split (4,4) gives a 31.2 MB file with the subject at 38.6 dB. The uneven split (2,6) gives 18.5 MB with the subject at 47.7 dB: 9.2 dB more on the subject for 41% fewer bytes. The background pays, dropping from 38.8 to 30.4 dB. Image: `docs/img/rd_video2_even_vs_uneven.png`.
- **Choosing n1 and n2.** With n1 fixed at 2, the subject stays at 47.7 dB for every n2 while the file falls from 93.9 to 13.7 MB. A simple rate rule follows: fix n1 for the quality wanted on the subject, then pick the smallest n2 that fits the available bitrate. Image: `docs/img/rd_video2_fixed_n1.png`.
- **Scale.** The bitrates are far above real streaming because the codec has no entropy coder and predicts at most every second frame. The result is about allocation: at any budget, the subject stays fixed and the background absorbs the cut.

## 8. Field clips

To test the method beyond the four course clips, I filmed five clips of my own on a phone (1080x1920 portrait, the same pixel count as the 1080p clips) and ran them through the frozen code with the default settings, nothing retuned. They are not part of the official results.

| Clip | Scene |
|---|---|
| cars_still | traffic, camera still |
| cars_both_ways | traffic in both directions, camera still |
| shoppers | many people indoors |
| scooter_followed | a scooter rider, camera panning to follow (29.97 fps) |
| scooter_fast | a fast scooter rider |

- **Speed.** All five played with 0 dropped frames and within 0.17% of the clip length; the 29.97 fps clip kept its exact rate because the header stores the rate as a fraction. Encoding took 40 to 149 s per clip.
- **Found well.** Cars crossing sideways, and the scooter rider, including while the camera pans to follow him: once the camera's motion is removed, his leftover motion is large and points one way.
- **Honest misses.**
  - A car driving head-on toward the camera grows instead of sliding, so its blocks barely move sideways and the edges move in opposite directions; the method does not see it.
  - A black shirt against black baskets has no texture, so its motion vectors cannot be trusted.
  - Two people walking side by side merge into one region, because they touch and move the same way, which the contiguity and consistency rule joins by design.
  - Poles close to the camera while panning slide faster than the background (parallax) and are sometimes labelled foreground, as with the video2 fence.

## 9. Extra credit

Both options are off by default; the baseline bytes are pinned by a golden test, so the graded format never changed. [P4-L2, P4-L3]

### 9.1 Zigzag and run-length coefficients (.cmp mode 1)
`./myencoder input.mp4 n1 n2 --mode rle` writes the same quantized coefficients in zigzag order (low frequencies first) and codes each run of zeros as one count, ending each block after its last nonzero value. The pictures are identical and the files are 29% to 40% smaller. Decoding is also faster, because most coefficients at step 2^5 are zero and are no longer stored one by one. [P4-L2]

### 9.2 Motion-compensated residual coding (.cmp mode 2)
Every second frame is predicted from the previous decoded frame, moved by one motion vector per 16x16 macroblock; only the difference (the residual) is transformed and quantized, with 2^n1 for foreground and 2^n2 for background as before, then stored with the mode 1 coding. The other frames are coded on their own. Groups of 2 were chosen after measuring lengths 2 to 8: with 2^n rounding, small changes are lost on each predicted frame, so longer groups were both larger and worse. Each pair decodes independently, so `./mydecoder` plays mode 2 in real time, including at 1080p. [P4-L1, P4-L3, RESULTS "P4-A"]

### 9.3 Results at (2,5)

| Clip | Baseline | Mode 1 (zigzag + run-length) | Mode 2 (motion-compensated) |
|---|---|---|---|
| video1 | 65.0 MB | 38.7 MB (-40%), same pixels | 32.4 MB (-50%), 37.49 / 47.77 / 37.39 dB |
| video2 | 85.4 MB | 60.9 MB (-29%), same pixels | 54.5 MB (-36%), 34.08 / 47.56 / 33.75 dB |
| Stairs | 291.0 MB | 193.9 MB (-33%), same pixels | 151.1 MB (-48%), 35.11 / 47.84 / 35.04 dB |
| SAL | 424.7 MB | 269.0 MB (-37%), same pixels | 207.3 MB (-51%), 34.24 / 47.31 / 34.20 dB |

Mode 2 halves the file on three of four clips, staying within 0.24 dB of the baseline on every clip and layer. All three modes decode faster than real time (1080p: baseline 77 to 82 fps, mode 1 95 to 105 fps, mode 2 79 to 88 fps). [RESULTS "Final Mac check"]

## 10. Limits

- **Distant, slow walker on Stairs.** 74 of 345 analysed frames have no foreground, mostly at the start while the walker is far away. He moves about 2 px per frame against the camera, near the 0.7 px error of the camera model and the whole-pixel precision of block matching, and his dark, flat jacket takes the camera's vector through the smoothness penalty. A lower threshold, an exact camera test, an adaptive threshold and motion summed over several frames were all measured; none found him without adding false foreground elsewhere. In those frames he is compressed with the background step. [P1-L16, P1-L21]
- **Parallax on video2.** The camera runs alongside the joggers, so the fence and trees close to the camera slide faster than the far background, and one camera model labels them foreground. A filter that drops regions moving along the camera's flow cuts video2's foreground from 6.95% to 4.15% (measured in the build environment), but it also deletes a real person walking against the camera. It is kept off by default, following the rule to lean toward foreground when unsure, because a blurred person is the mistake a viewer notices. Images: `docs/img/p1_parallax_video2_off.jpg`, `_on.jpg`. [P1-L20]
- **Uncovered background.** Blocks that a moving object has just revealed have no good match in the previous frame, so they can appear as small foreground fragments at the object's trailing edge. The match-quality gate and the 2 of 3 frame vote reduce this; it was not measured separately.
- **Articulated and approaching motion.** Arms and legs move in mixed directions and are found only in part; objects moving toward the camera (section 8) are missed.
- **One idea that did not work.** Predicting the whole background in mode 2 from the single camera homography instead of a vector per block was measured before building it. It predicted 21% better on SAL but 16% to 117% worse on the other three clips, which have depth. The pass bar was set before measuring, it failed, and the encoder was not built.

## 11. How to build and run

Requirements: Java 21 and FFmpeg (with ffprobe) on the PATH. On the project Mac, FFmpeg comes from conda, so activate conda base first or set the `FFMPEG` and `FFPROBE` environment variables.

```
cd layer-codec
./gradlew build                         # compiles and runs all 234 tests
./myencoder data/SAL.mp4 2 5            # writes data/SAL.cmp
./mydecoder data/SAL.cmp                # plays at the source frame rate
./mydecoder data/SAL.cmp data/SAL.mp4   # same, with the original for side by side
```

Player keys: space play/pause, right arrow step forward, left arrow step back, `s` side by side, `z` 1:1 zoom (drag to pan), `d` foreground overlay, `q` quit.

Optional, off by default: `./myencoder input.mp4 n1 n2 --mode rle` (mode 1) and `java -cp build/libs/layer-codec.jar codec.tools.McEncode input.mp4 n1 n2` (mode 2, writes `<name>_mc.cmp`). `./mydecoder` plays all three modes. On Windows use `myencoder.bat` and `mydecoder.bat` with the same arguments.

Inspection tools (not needed to grade): `codec.tools.VizSeg` draws segmentation overlays, `codec.tools.CmpDump` prints a frame in the brief's text layout, and `codec.tools.Psnr <mp4> <cmp>` measures PSNR per layer.

## 12. Tools used

- **Java 21** (Temurin JDK) for all code, built with the **Gradle** wrapper; **JUnit 5** for the 234 automated tests, including slow textbook versions of the DCT and the motion search that check the fast versions, and a golden test that pins the baseline file bytes.
- **FFmpeg / ffprobe** only to decode the input mp4 into RGB frames and read its metadata. No FFmpeg filters or encoders are used for compression, segmentation or display.
- **AI assistance** was used for planning the work, reviewing code, and drafting documents such as this report and the decision logs. Every design choice was measured, written down with its reasons and alternatives in `docs/decision-log/`, and is understood and defended by me.
