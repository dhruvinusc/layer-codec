# LEARN: the core project in 13 cards

Each card: max 150 words. Numbers are Mac numbers. Sources in brackets: `RESULTS "x"` = section of `docs/RESULTS.md`. Where only a sandbox number exists, it says so (not for slides).

---

## Card 1. Extra credit
**Idea.** Two optional modes, off by default. Mode 1: same numbers in zigzag order (low frequencies first), each run of zeros stored as one pair. Mode 2: every second frame is predicted from the previous one by motion vectors; only the difference is stored.
**Example.** video2 at (2,5): 85.4 MB baseline, 60.9 MB mode 1, 54.5 MB mode 2 [RESULTS "Final Mac check"].
**Decision.** Mode 2 in groups of 2 (one full, one predicted).
**Rejected.** Groups of 3 to 8: bigger and worse [RESULTS "P4-A"].
**Proof.** Mode 1: 29 to 40% smaller, identical pixels. Mode 2: 36 to 51% smaller, within 0.24 dB. [RESULTS "Final Mac check"]. `GoldenBaselineTest` pins baseline bytes.
**Say.** "Mode 1 is free; mode 2 trades a quarter dB for half the file."
**Prof asks:** why not longer groups? Small changes round away, so each predicted frame drifts. Measured 2 to 8: 2 was smallest at baseline quality.

## Card 2. The .cmp file
**Idea.** Binary file, same content and order as the brief's text layout. 48-byte header: magic `LYRC`, size, padded size, fps, frames, n1, n2. Each frame lists its 8x8 blocks in scan-line order (1 type byte + 64 R + 64 G + 64 B numbers, 16-bit), zipped with zlib (lossless), plus a frame table for stepping back.
**Example.** 1 + 3 x 64 x 2 = 385 bytes per block before zip. SAL (2,5): 424.7 MB vs 27.8 MB mp4 [RESULTS "Final Mac check"].
**Decision.** Binary, plus `CmpDump` to print the brief's text layout.
**Rejected.** Text file: no place for size, fps, frame count, and far larger.
**Proof.** `CmpFormatTest.blocksAreInScanLineOrder385BytesEach`.
**Say.** "Same content as the brief's layout, just binary and zipped; the zip loses nothing."
**Prof asks:** why bigger than the mp4? Every frame stands alone: no prediction, no entropy coding beyond zlib. Mode 2 shows prediction halves it.

## Card 3. Big picture
**Idea.** The encoder splits each frame into moving foreground and background, using motion only. Foreground gets a fine step 2^n1, background a coarse step 2^n2. The decoder reads only the .cmp and plays in real time.
**Example.** SAL at (2,5): foreground 47.52 dB, background 34.34 dB [RESULTS "Final Mac check"].
**Decision.** Hand-written Java 21; FFmpeg only reads mp4 frames.
**Rejected.** Person detectors or ML masks: the brief says motion only.
**Proof.** 234/234 tests pass; A1 to F2 ticked in `docs/SPEC_CHECKLIST.md`.
**Say.** "Spend bits where the eye looks: on what moves."
**Prof asks:** where is the loss? Only in rounding after dividing by 2^n. The DCT and zlib lose nothing: at n = 0 the result is 59 dB, rounding only [RESULTS "P2.9 Mac measurements"].

## Card 4. Reading video and padding
**Idea.** FFmpeg streams upright RGB frames and reports size, fps and rotation. Frames are padded to multiples of 16 by repeating the last column and row. The decoder crops the padding off.
**Example.** 1080 / 16 = 67.5, so 1080p pads to 1088 rows. 480 and 848 are already multiples of 16. SAL and Stairs are stored upside down, video2 sideways; rotation metadata turns them upright.
**Decision.** Repeat the edge pixels.
**Rejected.** Black padding: it adds a fake sharp edge (costly DCT detail, bad matches).
**Proof.** `PadTest`, `VideoIOTest`.
**Say.** "Make the frame fit whole blocks, then cut the extra off at the end."
**Prof asks:** why 16? Motion blocks are 16x16 and each holds four 8x8 DCT blocks, so 16 fits both. The padding never reaches the screen.

## Card 5. Motion vectors and fast search
**Idea.** For each 16x16 block of brightness (Y), find where it was in the previous frame; best match = smallest sum of absolute differences. Full search is too slow, so search coarse to fine at quarter, half and full size. A smoothness penalty prefers vectors that agree with neighbours.
**Example.** 190 ms per 1080p frame, target 200 ms [RESULTS "W2 / D13 Mac numbers"].
**Decision.** Hierarchical search with smoothness penalty.
**Rejected.** Full search as default (kept as the reference).
**Proof.** Agrees with exact search on 89.9% (SAL) to 95.9% (video2) of textured blocks; real misses 1.6 to 4.0% [RESULTS "W2 / D13 Mac numbers"].
**Say.** "Find each block in the last frame, coarse to fine, like zooming a map."
**Prof asks:** why Y only? Brightness carries the texture; three channels would triple the cost. The DCT still runs on R, G, B.

## Card 6. Camera motion (RANSAC, homography)
**Idea.** All 4 clips have a moving camera, so the background moves too. Fit a homography (a 3x3 map covering pan, tilt, rotation, perspective) to trusted vectors with RANSAC (try many small random samples, keep the model most vectors agree with). Subtract the camera's vector at each block to get leftover motion.
**Example.** 500 samples, fixed seed, inlier within 2.5 px. Trusted block: Y variance at least 100, match error (MAD) at most 12.
**Decision.** Homography, falling back to affine, then one vector.
**Rejected.** The brief's single constant vector: fails on rotation and perspective.
**Proof.** `GlobalMotionTest`. (Accepted on 1679 of 1680 frame pairs is a sandbox number [RESULTS "P1.5 camera motion"], not for slides.)
**Say.** "First explain away the camera; what is left over is the object."
**Prof asks:** why RANSAC, not least squares? Moving people are outliers that would pull a least-squares fit. RANSAC ignores them.

## Card 7. Choosing foreground
**Idea.** A candidate block has leftover motion over 2.5 px, a good match, texture, and the camera's vector does not fit it almost as well. Candidates join into connected regions only if their motion agrees; tiny (under 0.2% of frame) or scattered (spread over 3 px) regions are dropped. Then fill holes; keep blocks foreground in 2 of 3 frames.
**Example.** Naive vs final, same vectors: SAL 8.68% to 1.07%, video2 16.56% to 7.25% [RESULTS "Mac re-measure (Nov 16)"].
**Decision.** Thresholds tuned on all 4 clips together, checked by eye.
**Rejected.** Growing every region by one block: doubled foreground.
**Proof.** `docs/img/naive_*.jpg` vs `docs/img/p1_final_*.jpg`; `Segmenter*Test`.
**Say.** "Moving is not enough: blocks must move together and keep moving."
**Prof asks:** why the "camera fits almost as well" test? Repeating bricks can lock one brick over and fake motion. If the camera vector fits nearly as well, it is not real.

## Card 8. DCT
**Idea.** Each 8x8 block of R, G and B becomes 64 frequency numbers: top-left is the average, further right and down is finer detail. It is the orthonormal DCT from class, after shifting values by 128. The DCT itself loses nothing.
**Example.** At n = 0 only rounding is lost: 59 dB [RESULTS "P2.9 Mac measurements"].
**Decision.** DCT on R, G, B as the brief describes.
**Rejected.** A YCbCr colour pipeline (Y is used only for motion).
**Proof.** `DctTest`; `docs/img/dct_SAL_f150.png` (energy, walker vs wall).
**Say.** "The DCT sorts a block into smooth parts and detail, so detail can be dropped cheaply."
**Prof asks:** why 8x8? It is the block size in the brief and in class. Small blocks keep errors local.

## Card 9. Quantization 2^n
**Idea.** Divide every coefficient by 2^n and round half away from zero; the decoder multiplies back. The same step is used for all 64 coefficients, as the brief asks. This rounding is the only loss in the codec.
**Example.** Coefficient 37. n = 2 (step 4): 37/4 = 9.25, stored 9, back 36, error 1. n = 5 (step 32): 1.16, stored 1, back 32, error 5.
**Decision.** One step per block type, chosen by the type byte.
**Rejected.** A JPEG-style table with a different step per frequency (brief asks one step).
**Proof.** `QuantTest`, `DecodeCoreTest.blockTypeChoosesTheExponent`.
**Say.** "Bigger n, bigger step, more rounding, more zeros, smaller file."
**Prof asks:** why does the zip shrink at high n? Most coefficients round to 0, and repeats zip well. zlib saves 3x to 9.6x depending on n [RESULTS "P2.9 Mac measurements"].

## Card 10. Choosing n1 and n2
**Idea.** n1 < n2 makes the foreground cleaner than the background. Swapping them swaps the effect. On SAL about 99% of blocks are background, so n2 sets the file size.
**Example.** SAL (2,5): foreground 47.52 / background 34.34 dB, 424.7 MB. (5,2): 34.40 / 47.72 dB, 1142.8 MB [RESULTS "Final Mac check"].
**Decision.** Demo at (2,5).
**Rejected.** Even bits like (4,4): (2,6) gives the subject 9.2 dB more for 41% fewer bytes [RESULTS "Mac re-measure (Nov 16)", lab L3].
**Proof.** `docs/img/p3r_SAL_swap_crop_x3.png`.
**Say.** "n1 decides how the person looks; n2 decides how big the file is."
**Prof asks:** what if n1 > n2? The background becomes cleaner than the person, as the swap shows, and the file grows because the large background now gets the fine step.

## Card 11. Player and threads
**Idea.** One 1080p frame is too slow on one core for 30 fps. So 4 workers decode whole frames in parallel into numbered slots, and the player shows them in order on a clock. Late frames are dropped and counted on screen.
**Example.** SAL: 42.4 ms per frame on one core vs a 33.3 ms budget; 81.0 fps with 4 workers [RESULTS "P3.R quiet-Mac run"].
**Decision.** Parallel whole frames, played on a clock.
**Rejected.** Decoding inside the play loop on one thread: too slow for 1080p.
**Proof.** `DecodePipelineTest.everyFrameIdenticalToSerialDecode`; all 12 files 0 dropped, within 0.10% of clip length [RESULTS "Final Mac check"].
**Say.** "Four cooks plate ahead; one waiter serves a plate every 33 ms."
**Prof asks:** how do frames stay in order? Each has a numbered slot and the player waits for the next number. The test proves output equals one-thread decoding, frame by frame.

## Card 12. Tests and results
**Idea.** 234 JUnit tests check each piece and the whole chain. A golden test pins the baseline file bytes. `tools/final_check.sh` reruns everything on the Mac.
**Example.** Final Mac check, commit 88fc181: 234/234 pass; 4 clips x 3 modes encode and play, 0 dropped [RESULTS "Final Mac check"].
**Decision.** Mac numbers are the official ones.
**Rejected.** Sandbox timings (different machine, different FFmpeg).
**Proof.** Worst encode 130 s (SAL at (5,2)) plus real-time play, limit 10 min [RESULTS "Final Mac check"].
**Say.** "Every claim on my slides has a test name or a measured number behind it."
**Prof asks:** how do you know the decoder is right? Tests check block order, block type and exponent, and that parallel decoding equals serial. PSNR per layer then measures quality against the original.

## Card 13. Limits
**Idea.** A slow, distant walker on Stairs is missed: he moves about 2 px per frame against the camera, close to the model's error. On video2 the near fence slides faster than the far background (parallax) and gets marked foreground. Arms and legs moving in mixed directions are found only in part.
**Example.** Stairs: 74 of 345 frames have no foreground [RESULTS "Mac re-measure (Nov 16)"].
**Decision.** Parallax filter built but OFF: it also deletes a real person walking against the camera. When unsure, lean foreground.
**Rejected.** Lower threshold, exact camera test, adaptive threshold, multi-frame motion: each found little new; multi-frame raised video2 false foreground.
**Proof.** `docs/img/p1_parallax_video2_off.jpg` and `_on.jpg`.
**Say.** "A blurry person is the mistake people notice; a sharp fence just costs bytes."
**Prof asks:** how would you fix Stairs? Sub-pixel motion search, so a 2 px move stands out from the camera model's error.
