# 25 hardest questions (two-sentence answers + proof)

Mac numbers. Sources: HANDOFF = `claude/08`, REPORT = `docs/final_report_draft.md`, LAB = `lab/story/02-lab-story.md`.

## Segmentation
1. **Your clips all have a moving camera. How is that still "background = near-zero motion"?**
The brief allows one constant vector for a moving camera; I generalised it to a homography so pan, tilt and perspective count as camera motion too. Background is whatever the camera explains, foreground is what is left over. *Proof:* homography accepted on 1679 of 1680 frame pairs, explains 74 to 90% of trusted blocks [REPORT 3.2].

2. **Why a homography and not affine or translation?**
A homography is exact for a flat surface under any camera motion, and Stairs is mostly flat walls and steps seen in perspective. On Stairs translation explains 56 to 76% of blocks, the homography 89 to 95%. *Proof:* `claude/05` camera-model table; `GlobalMotionTest.homographyWithThirtyPercentOutliersRecoveredWithinHalfPixel`.

3. **RANSAC is random. Is your output random?**
No: fixed seed and fixed 500 iterations, so the same input gives byte-identical labels. Across machines labels differ by 0.11% only because a different FFmpeg decodes slightly different pixels. *Proof:* `GlobalMotionTest.sameInputGivesIdenticalModel`; SHA-256 match [REPORT 3.2].

4. **How do you know the fast search finds the right vectors?**
I kept a full search as the reference and compared: they agree within 1 px on 89.9% (SAL) to 95.9% (video2) of textured blocks. Most SAL disagreements are equally good matches on repeating bricks; real misses are 1.6 to 4.0%. *Proof:* RESULTS "W2 / D13 Mac numbers"; `MotionEstimatorTest.matchesTextbookOracleOnMixedContent`.

5. **Why hierarchical search, and how far can it reach?**
video2's camera moves 16+ px per frame; a full search of plus or minus 32 is 4225 positions per block, 1.24 s per 1080p frame already at plus or minus 16. Searching plus or minus 8 at quarter size covers about 32 px for a fraction of that: 190 ms per 1080p frame. *Proof:* 139.6 ns per SAD [05]; RESULTS "W2 / D13".

6. **Why SAD when the brief says MAD?**
MAD is SAD divided by 256, a constant, so both pick exactly the same vector. SAD stays an integer, so comparisons are exact and ties are deterministic. *Proof:* comment `MotionEstimator.java:12-13`; `sadZeroAndMadAreReportedCorrectly`.

7. **What does "consistent direction" actually remove?**
Bad matches (bricks, stripes, flat areas) give vectors pointing in random directions, so neighbours rarely agree and the region never forms. A real walker's blocks share one leftover motion. *Proof:* naive vs final on the same vectors: SAL 8.68% to 1.07%, video2 16.56% to 7.25% [HANDOFF 2]; `SegmenterGroupTest`.

8. **Is naive vs final a fair comparison?**
Yes: same seeded vectors, same camera model, only the rule differs. The drop comes from the consistency rule, the camera-match test and the 2 of 3 frame vote. *Proof:* LAB L2 replay matches `Segmenter.Run` on every drawn frame.

9. **What is the red on the video2 trees and fence?**
Parallax: the camera runs beside the joggers, so near things slide faster than the far background and one camera model cannot explain both depths. I built a filter that removes it but kept it off because it also deleted a real person walking against the camera. *Proof:* `docs/img/p1_parallax_video2_off.jpg` vs `_on.jpg`; 6.95 to 4.15% (sandbox) [REPORT 7].

10. **Show me a failure.**
Stairs: the distant walker moves about 2 px against the camera, near the model's 0.7 px error, so 74 of 346 frames have no foreground and he gets the coarse step there. I tried four fixes; none found him without adding false foreground elsewhere. *Proof:* RESULTS "Mac re-measure (Nov 16)", P1-L16, P1-L21.

11. **Why lean toward foreground when unsure?**
A false background blurs a person, which a viewer notices; a false foreground only costs bytes. That is also why the parallax filter is off. *Proof:* 03 D3, P1-L20.

12. **What happens with a zooming camera?**
A zoom makes vectors point outward from the centre; a homography models zoom exactly, so background still fits. A single constant vector would fail there, which is one reason I did not use it. *Proof:* homography has scale terms; `fourExactPointsGiveTheExactHomographyAtFullHdScale`. (Not tested on a real zoom clip; say so.)

13. **Uncovered background: what happens?**
Blocks a person just revealed have no good match in the previous frame, so their vectors are unreliable and can appear as small fragments at the trailing edge. The match-quality gate and the 2 of 3 vote reduce it; I did not measure it separately. *Proof:* REPORT 7.

## Compression and file
14. **Where exactly is information lost?**
Only in rounding c / 2^n to a whole number; the DCT, zlib and the file are lossless. At n = 0 only rounding to integers is lost: 59 dB. *Proof:* `Quant.java:31-35`; `DctTest.forwardThenInverseReturnsInput`; RESULTS "P2.9".

15. **Compute the DC of a flat block of 200.**
Shift by 128 gives 72 per pixel; the orthonormal 8x8 DC is 8 times the mean, so 576, and all AC terms are 0. *Proof:* `DctTest.flatBlockOf200HasDc576AndNoAc`.

16. **Why is your .cmp bigger than the mp4?**
The baseline stores every frame on its own with 16-bit coefficients and only zlib; H.264 predicts almost every frame from others and uses an entropy coder. video2 at (2,5) is 85.4 MB against a 2.7 MB mp4; mode 2 brings it to 54.5 MB. *Proof:* REPORT 6.2, HANDOFF 2.

17. **Why binary and not the text layout I suggested?**
Same content in the same order, but the text layout has no size, frame rate or frame count, and SAL as text would exceed 15 GB. CmpDump prints any frame in your exact layout. *Proof:* demo menu 8; `CmpFormatTest.blocksAreInScanLineOrder385BytesEach`.

18. **Isn't zlib entropy coding, which you said is not expected?**
It is lossless general zipping, so it changes no pixel; the brief says entropy coding is not expected, not forbidden. It saves 3 to 9.6 times, and mostly on the zeros the background quantizer creates. *Proof:* RESULTS "P2.9".

19. **Why the same step for all 64 coefficients instead of a JPEG table?**
The brief requires every table entry to be 2^n, uniform. A perceptual table would look better but break C3. *Proof:* `claude/01` C3; 03 D6.

20. **Swapping n1 and n2 almost triples the SAL file. Why?**
The background is about 99% of blocks, so n2 sets the size: at step 32 most background coefficients round to 0 and zip almost free, at step 4 they survive. 424.7 MB at (2,5), 1142.8 MB at (5,2). *Proof:* RESULTS "Final Mac check"; LAB L5.

## Decoder and player
21. **One 1080p frame takes 54 ms. How do you play at 30 fps?**
Four workers decode whole frames in parallel into numbered slots, and the player shows frame i at start plus i over fps. That gives 77 to 82 fps on 1080p, bit-identical to serial decoding. *Proof:* `DecodePipelineTest.everyFrameIdenticalToSerialDecode`; HANDOFF 2.

22. **What if decoding falls behind?**
The late frame is dropped and counted, so the clock never slips and the original rate holds. In every real run on the Mac, 0 frames were dropped and duration was within 0.10%. *Proof:* `PlayerTest.slowDecodeDropsFramesButKeepsTheOriginalRate`; final Mac check [HANDOFF 2].

23. **How does the decoder know what is foreground without your segmenter?**
Every 8x8 block carries a type byte in the file; the decoder reads it to pick n1 or n2 and to tint the overlay. No labels file and no segmentation code is used. *Proof:* `DecodeCore.java:97`; `DecodeCoreTest.blockTypeChoosesTheExponent`.

## Extra credit and lab
24. **Your bitrates are 60 to 150 Mbps. How is this "for streaming"?**
The codec has no entropy coder and predicts at most every second frame, so it cannot compete with H.264 on raw bitrate. The claim is about allocation: at any budget, fixing n1 keeps the subject at 47.7 dB and only the background pays; at the same size the uneven split gives the subject 9.2 dB more for 41% fewer bytes. *Proof:* `docs/img/rd_video2_even_vs_uneven.png`, LAB L3.

25. **You used AI tools. What did you do yourself?**
AI helped with planning, code review and drafting documents; that is stated in the report. Every decision is logged with its options and a measured number, and I can walk through any line here. *Proof:* REPORT 10; `docs/decision-log/`; 234 tests.
