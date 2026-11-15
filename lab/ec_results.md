# L4 lab: camera-predicted background (mode 3), results

Window: L4 Extra Credit Lab. Started Nov 15. Code: `lab/src/lab/ec/CamProbe.java` (lab only, calls frozen P1 code, changes nothing in `src/`).

## Step 1 (Nov 15): prediction error only. Result: NO-GO by the pre-set criterion

Question: for BACKGROUND macroblocks of a P frame, does the frame's camera homography (8 numbers) predict them better than each block's own motion vector (what mode 2 uses)?

Setup: 5 frame pairs per clip at 10/30/50/70/90% (odd frames, so P frames at GOP 2). Labels, vectors and homography from the frozen P1 pipeline, replayed call for call as `Segmenter.Run` (6 warm-up frames, temporal vote included). SAD on Y per 16x16 block against the previous original frame. Sandbox numbers (SAD is not a timing, so the sandbox is fine).

Methods: MV = own integer vector (mode 2). HINT = homography at the block center rounded to one integer vector. HWARP = homography per pixel with bilinear sampling (sub-pixel, follows perspective).

Criterion, written into lab/README.md before running: HWARP mean background SAD at least 10% below MV on at least 3 of 4 clips, and not more than 5% worse on any.

| Clip | bg blocks | SAD zero | SAD MV | SAD HINT | SAD HWARP | HWARP / MV | blocks where HWARP wins | min(MV, HWARP) / MV |
|---|---|---|---|---|---|---|---|---|
| video1 | 7,834 | 2084 | 484 | 835 | 798 | 1.65 (worse) | 10% | 0.96 |
| video2 | 7,456 | 5313 | 1163 | 2692 | 2522 | 2.17 (worse) | 17% | 0.95 |
| Stairs | 40,397 | 5315 | 822 | 1161 | 954 | 1.16 (worse) | 55% | 0.82 |
| SAL | 40,278 | 3011 | 769 | 894 | 605 | 0.79 (better) | 73% | 0.72 |

Only SAL passes. Verdict: STOP before step 2 (CamEncode / CamDecode not built).

Side information saved, for scale: background vectors after zlib cost 1.1 to 5.5 KB per P frame (all vectors 1.4 to 5.7 KB, foreground only 0.1 to 0.3 KB). A mode 2 P frame is about 140 KB (video2) to 480 KB (SAL), so dropping background vectors alone saves under 1% of the file. Any real gain would have had to come from smaller residuals, which is what step 1 measured.

Why it fails (look at lab/img/step1_map_*.jpg; green = warp better, red = own MV better, blue = foreground):
1. One homography is exact only for one flat plane. video1 and video2 are deep handheld scenes: near ground, fence and trees all move differently (parallax). Inlier ratios 0.50 to 0.94. The per-block vector follows each depth; one camera model cannot.
2. "Background" is whatever the segmenter did not call foreground. Missed movers (the video2 joggers in f191, the SAL walker's legs in f219) are labelled background, and no camera model predicts them.
3. 480 x 848 clips have few textured blocks, so the fit rests on fewer points and misses by 1 to 3 px, which costs a lot of SAD.
4. Where it works (SAL wall, trees, far path): the scene is close to planar and the warp gets sub-pixel accuracy, which an integer vector cannot. Part of that win may simply be the bilinear filter smoothing noise, not better alignment (not separated here).

What would be worth trying instead (NOT started, needs PM): use the camera warp as one extra candidate per background block, with a 1-bit flag (warp or own vector). The oracle best-of column shows the ceiling: 28% lower SAD on SAL, 18% on Stairs, 4 to 5% on the 480p clips, before the flag cost and before quantization (at n2 = 5 many background residuals already round to zero, so bytes would gain less than SAD). This is a different idea from "segmentation drives prediction", so it is a new decision, not a continuation.

Say it in the presentation: "We tried predicting the whole background with one camera model instead of a vector per block. It wins on the near-flat SAL scene but loses badly on the deep handheld clips, because one plane cannot describe parallax, so we kept per-block vectors."

Likely professor question: Why not use a homography, you already compute one? / Answer: We measured it on all four clips. It predicts 21% better on SAL but 16% to 117% worse elsewhere, because a homography only models a single plane and our backgrounds have depth.

Raw data: lab/out/ec/step1_blocks.csv (per block), step1_frames.csv (per frame), step1_console.txt.
