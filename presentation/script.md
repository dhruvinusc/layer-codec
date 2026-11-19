# Talk script: Video Layer Based Compression (Dec 2, 10 minutes)

Every number is a Mac number. The source is in brackets: HANDOFF = `claude/08`, REPORT = `docs/final_report_draft.md`, LAB = `lab/story/02-lab-story.md`, DEMO = `demo/DEMO.md`.
Pace: about 140 words per minute. Times in brackets are when each slide should end.
Each slide has: SAY, DECISION, PICTURE (why it looks that way), CODE (one shot; show only if asked or if time allows).

---

## Slide 1. The problem (0:30, ends at 0:30)

**SAY**
"A normal codec spends the same care on every part of the frame. But people watch the thing that moves. The brief asks me to find the moving objects from motion alone, and give them a fine quantization step, 2 to the n1, and everything else a coarse step, 2 to the n2. Then the decoder must play it back at the original frame rate. My question for the whole talk: where do the bits go, and what does that buy?"

**DECISION**
Brief fixes the idea; our framing comes from your own words: "distributing compression bits unevenly between foreground and background for streaming". Rejected: telling the talk in build order (segmentation, then DCT, then decoder). Why: the bit question is what you asked.

**PICTURE**
One SAL frame, walker tinted, wall plain. Simple on purpose.

**CODE** `src/main/java/codec/common/Quant.java:26`
```java
return 1 << n;
```
- The whole quantizer step is a power of two: 1 shifted left n times. n = 2 gives step 4, n = 5 gives step 32.

---

## Slide 2. Pipeline (1:00, ends at 1:30)

**SAY**
"Here is the pipeline. FFmpeg only reads the mp4 into upright RGB frames; everything after that is my own Java. I pad each frame to a multiple of 16, so 1080 becomes 1088. On the brightness channel I find one motion vector per 16 by 16 block against the previous frame. I fit the camera's motion, subtract it, and the blocks that still move, and move together, become foreground. Then each 8 by 8 block gets a DCT on R, G and B, is divided by 2 to the n1 or n2 and rounded, and goes into a .cmp file. The decoder multiplies back, inverts the DCT, and plays."

**DECISION**
- Motion on Y only, DCT on R, G, B (brief B5, C2). There is no YCbCr compression path. Rejected: DCT in YCbCr, because the brief says R, G, B.
- Java 21 + FFmpeg as a frame reader only (03 D1). Rejected: OpenCV, because then a library would do graded work.
- Pad by copying the edge row (03 D5). Rejected: cropping, because it would lose 8 real rows.

**PICTURE**
Arrow diagram. Two colors: motion side (encoder only) and pixel side (encoder and decoder).

**CODE** `src/main/java/codec/common/CmpFormat.java:139-142`
```java
int per = Types.MB / Types.BLK;                      // 16 / 8 = 2
for (int by = 0; by < blocksY; by++) {
    for (int bx = 0; bx < blocksX; bx++) {
        t[by * blocksX + bx] = (byte) (labels.get(bx / per, by / per) != 0 ? 1 : 0);
```
- `per` is 2: two 8x8 blocks fit across one 16x16 macroblock.
- Loop over every 8x8 block in scan line order (row by row).
- Dividing the 8x8 position by 2 gives its macroblock; that label becomes the type byte (brief C1). Real encodes: 0 mismatches over 34.1 million blocks [REPORT 4].

---

## Slide 3. Segmentation (1:30, ends at 3:00)

**SAY**
"All four clips have a moving camera, so 'background' can't mean 'not moving'. Background means 'moving the way the camera makes it move'. I fit a homography, an 8-number model of camera pan, tilt and perspective, using RANSAC: many random small samples vote, and the moving people are outvoted. Leftover motion is each block's vector minus the camera's vector at that block. A block is a candidate if that leftover is over 2.5 pixels. Candidates only join a region if their leftover agrees with their neighbour in size or direction, that is the brief's contiguous and consistent rule. Then a block must be foreground in 2 of 3 frames. On the left is a naive rule on the same vectors: SAL lights up 8.68 percent of blocks, the brick wall. My final rule: 1.07 percent, and the wall is clean. video2 goes from 16.56 to 7.25 percent."

**DECISION**
- Homography with RANSAC, fallback affine then translation (03 D3, P1-L11). Rejected: the brief's "one constant vector", because a single translation explains only 56 to 76% of Stairs blocks, the homography 89 to 95% [05].
- Lean foreground when unsure (03 D3). A blurred person is the error a viewer sees; false foreground only costs bytes.
- Thresholds tuned on all 4 clips together, never per clip; one "best by numbers" setting was rejected because it erased the joggers [REPORT 3.1].

**PICTURE** (`docs/img/naive_SAL.jpg` vs `docs/img/p1_final_SAL.jpg`)
- Naive: big colored blobs on the brick wall and on the red paving grid. Bricks repeat every few pixels, so a block can match "one brick over" almost as well as its true spot. That fake vector disagrees with the camera, so the naive rule calls it foreground.
- Final: the wall is clean because of two tests: the camera's own vector must not match almost as well (kills "one brick over"), and neighbours must agree.
- If you show video2 (`p1_final_video2.jpg`): red and blue on the near tree trunk and the fence is **parallax**. The camera runs beside the joggers, so near things slide faster than far ones, and one flat camera model cannot explain both. The joggers are only partly colored: the dark flat shirt has little texture, so its vector is unreliable, and arms and legs move in mixed directions.

**CODE** `src/main/java/codec/encoder/Segmenter.java:91-93` and `:498-499`
```java
cam.predict(cx_center, cy_center, v);        // camera's vector at this block's centre
rx[i] = (float) (mv.dx()[i] - v[0]);          // leftover x = measured minus camera
ry[i] = (float) (mv.dy()[i] - v[1]);          // leftover y
...
int votes = (c != 0 ? 1 : 0) + (p != 0 ? 1 : 0) + (n != 0 ? 1 : 0);
out[i] = votes >= 2 ? (c != 0 ? c : p != 0 ? p : n) : 0;
```
- Line 91 (shortened here): ask the camera model where this block should have moved.
- Lines 92-93: what is left over is the block's own motion.
- Line 498: count foreground in previous, current, next frame (looked up along the motion vector, because video2's camera moves up to two blocks per frame).
- Line 499: 2 of 3 wins; a one-frame flicker dies.

---

## Slide 4. DCT and 2^n (1:30, ends at 4:30)

**SAY**
"This is one real walker block and one real wall block from SAL frame 150. The DCT rewrites 64 pixels as 64 frequency weights; it loses nothing, rebuilding gives the pixels back. The only loss is next: divide by 2 to the n and round. At n equals 2, 19 of the walker's 64 weights become zero and the block rebuilds at 46.4 dB. At n equals 5, 53 of 64 are zero and it drops to 32.7 dB, you can see the edges smear. With (2,5), the walker gets the first column and the wall gets the second. That is the whole trade."

**DECISION**
- Orthonormal 8x8 DCT with level shift 128, the class formula (03 D6). Checked against a slow textbook version in tests.
- Round half away from zero, not truncate. Rounding has about half the average error (`QuantTest.roundingHasAboutHalfTheErrorOfTruncation`).
- Same step for all 64 coefficients, because the brief says so. Rejected: a JPEG-style table; it would be better visually but breaks C3.

**PICTURE** (`docs/img/dct_SAL_f150.png`)
- Big numbers sit top left (low frequencies): natural images are mostly smooth.
- Column (d) is mostly zeros: at step 32 small weights round to 0. Zeros are why zlib shrinks the background so much.
- Both blocks were picked at the same texture level (G variance 628 and 602), so the comparison is fair.

**CODE** `src/main/java/codec/common/Quant.java:31-35`
```java
double x = c / step(n);                        // divide by 2^n
long q = (long) Math.floor(Math.abs(x) + 0.5); // round the size to nearest
if (x < 0) {
    q = -q;                                    // put the sign back: -2.5 -> -3, symmetric
}
```
- This is the only place information is lost in the baseline codec.
- Working on the absolute value makes rounding symmetric, so negatives do not drift toward zero.

---

## Slide 5. File and decoder (0:30, ends at 5:00)

**SAY**
"The file is binary but in the brief's order: n1, n2, then per block a type byte and 64 R, 64 G, 64 B numbers, each frame zipped losslessly. I added a header with size, frame rate and frame count, because the decoder must need nothing else. One 1080p frame takes 54 ms on one core, more than the 33 ms budget, so four workers decode whole frames in parallel and a clock shows them in order: 77 to 82 frames per second."

**DECISION**
- Binary with a 48-byte header (03 D7). Rejected: the brief's text layout, because it has no size or fps, and SAL as text would exceed 15 GB. `CmpDump` prints the exact text layout on demand.
- Frame-parallel decode (03 D8). Rejected: splitting one frame across cores, which helped less and nests threads.
- Late frame is dropped, never slowed: holds the original rate (brief D4).

**PICTURE**
Byte table of the header plus one block (1 + 64 x 3 x 2 = 385 bytes).

**CODE** `src/main/java/codec/decoder/DecodePipeline.java:154` and `Player.java:208-209`
```java
while (!ready.containsKey(k)) { ... wait ... }               // DecodePipeline 154
long behind = (now.getAsLong() - t0) * periodDen / periodNum; // Player 208
int target = (int) Math.min(frameCount - 1, Math.max(next, i0 + behind)); // 209
```
- Workers finish in any order; the player asks for frame k and waits for exactly that slot, so order is kept.
- Line 208: how many frame periods have passed since the clock started.
- Line 209: show the frame that is due now; anything skipped is counted as dropped. The real runs dropped 0.

---

## Slide 6. LIVE DEMO (2:00, ends at 7:00)

**>>> DEMO STARTS HERE. Switch to Terminal; the menu (`bash demo/demo.sh`) is already open, items 0 and 1 done before the talk.**

| Time | Press | Say |
|---|---|---|
| 5:00 | **menu 2** | "The brief's exact command: myencoder, video2, n1 = 2, n2 = 5." While it runs (about 20 s): "It is doing motion search, camera fit, labels, DCT, all of it." |
| 5:25 | read `encoded` line | "383 frames, 85.4 MB, far under the 10 minute limit." |
| 5:30 | **menu 4** | "The decoder needs only the .cmp. I also pass the original for comparison." |
| 5:35 | `space` | "Original 30 frames per second." |
| 5:45 | `space`, `right`, `right`, `left` | "Pause, step, step, step back." |
| 5:55 | `s` | "Left decoded, right original." |
| 6:05 | `d` | "Tinted blocks are foreground. The decoder knows this from the type byte, not from any segmentation code." |
| 6:15 | `z`, drag over a jogger | "At 1:1 the jogger, step 4, is clean; the trees, step 32, are blocky." |
| 6:40 | `q`, read `play` line | "Zero dropped frames, timing within 0.1 percent." |
| 6:50 | back to slides | |

If the demo fails: say "here is the same run recorded", play the backup video. Do not debug live (see `demo/DEBUG.md`).

**DECISION**
Demo video2, not SAL: it encodes in 21 s, so the encode can be live. SAL is pre-encoded for the swap.

**PICTURE**
At 1:1 the background blocks look like 8x8 tiles: step 32 zeroed most high frequencies, so each tile is close to flat with a few soft waves.

**CODE** (only if asked "show the motion search") `src/main/java/codec/encoder/MotionEstimator.java:117-121`
```java
sum += Math.abs(cur[c + k] - prev[p + k]);  // add up brightness differences
...
if (sum > limit) {
    return sum;                             // already worse than the best: stop early
}
```
- SAD over 256 pixels. MAD is SAD / 256, so both pick the same vector.
- Early exit after each row saves time without changing the answer.

**<<< DEMO ENDS.**

---

## Slide 7. Result: the swap (1:00, ends at 8:00)

**SAY**
"The brief says the effect must reverse when n1 and n2 are swapped. This is SAL, three times, at 1:1. At (2,5) the walker is 47.5 dB and the background 34.3. At (5,2): walker 34.4, background 47.7. Same frames, same labels; the step size alone decides who looks good. And it costs very differently: 425 MB at (2,5), 1143 MB at (5,2), because the background is 99 percent of the blocks."

**DECISION**
Measure PSNR per layer, not overall (D10). Rejected: one overall PSNR, because with 1% foreground it would hide the effect entirely (overall SAL is 34.38 dB, almost the background value).

**PICTURE** (`docs/img/p3r_SAL_swap_crop_x3.png`)
The blockiness jumps: on the wall in the middle panel, on the walker in the right panel. Zoomed 3x so the 8x8 grid is visible from the back of the room.

**CODE** `src/main/java/codec/decoder/DecodeCore.java:96-97`
```java
// D2: the block's own type decides its quantizer step.
int n = (types[b] & 0xFF) == 1 ? h.n1() : h.n2();
```
- `& 0xFF` turns Java's signed byte into 0..255.
- Type 1 uses n1, type 0 uses n2. Swapping the arguments swaps which blocks get the fine step. Test: `DecodeCoreTest.blockTypeChoosesTheExponent`.

---

## Slide 8. Lab: does the uneven split pay? (1:00, ends at 9:00)

**SAY**
"After the freeze I asked your question directly: at the same file size, does uneven beat even? Blue is the even split, both layers the same n. Green keeps the walker at n1 equals 2 and makes only the background coarser. At (4,4) the file is 31.2 MB and the subject is 38.6 dB. At (2,6) the file is 18.5 MB and the subject is 47.7 dB. That is 9.2 dB more on the subject for 41 percent fewer bytes; the background pays. For streaming, I keep n1 fixed and pick the smallest n2 that fits the bitrate."

**DECISION**
- Rate control: fix n1, search n2 (LAB L3). Rejected: searching both, because the subject's quality should not move with the budget.
- All lab tools call the frozen jar; `src/` unchanged since `freeze-code` 88fc181 (LAB L0).

**PICTURE** (`docs/img/rd_video2_even_vs_uneven.png`)
- Green line is flat: n1 does not change, so subject quality does not change, only the file size slides left as n2 grows.
- Blue curve falls fast to the left: even split must hurt the subject to save bytes.
- Same shape in all three modes; better modes just shift everything left (smaller).

**CODE** `lab/rd/src/lab/rd/RateControl.java:130-138`
```java
int lo = n1;
int hi = Quant.MAX_N;                    // invariant: hi fits the budget
while (lo < hi) {
    int mid = (lo + hi) / 2;
    if (probe(segs, n1, mid, ...).mbps[mode] <= target) hi = mid; // fits: try finer
    else lo = mid + 1;                                             // too big: coarser
```
- Binary search over n2 (bitrate always falls as n2 grows), at most 4 probes for n2 in 0..10.
- Returns the finest background that fits; the subject stays at n1.

---

## Slide 9. Extra credit (0:30, ends at 9:30)

**SAY**
"Two extras, both off by default. Zigzag plus run-length: same pixels, 29 to 40 percent smaller. Motion-compensated: every second frame stores only the difference from the previous one, 36 to 51 percent smaller within 0.24 dB. A golden test proves the default file never changed."

**DECISION**
- Prediction group of 2 (P4-A). Rejected: longer groups, which were both bigger and worse, because 2^n rounding drifts on each predicted frame.
- Behind flags; `GoldenBaselineTest` pins the default bytes.

**PICTURE** (`docs/img/sizes_4clips.png`)
Three bars per clip; mode 2 roughly halves three of four clips. 1080p bars are much taller: about 5 times the pixels.

**CODE** `src/main/java/codec/common/RunLength.java:66-73`
```java
short c = coef[base + ZIGZAG[i]];   // read in zigzag: low frequencies first
if (c == 0) {
    run++;                          // count zeros instead of storing them
} else {
    out.write(run);                 // store "how many zeros", then the value
```
- Zigzag puts the zeros together at the end; the block then stops with one end marker.
- Surprise worth knowing: at n2 of 0 or 1 this is bigger than baseline (3 bytes per nonzero vs 2), because there are no zeros.

---

## Slide 10. Limits and next (0:30, ends at 10:00)

**SAY**
"What does not work. On Stairs the distant walker moves about 2 pixels against the camera, close to the model's error, so 74 of 346 frames have no foreground. On video2, near fence and trees slide past because of parallax; I built a filter but kept it off because it also deleted a real person. And one lab idea failed: predicting the whole background from the camera model won on SAL and lost on the other three, so I did not build it. Next: an entropy coder and sub-pixel motion. Thank you."

**DECISION**
Parallax filter OFF by default (P1-L20): cuts video2 foreground 6.95 to 4.15% (sandbox) but deletes a person walking against the camera. Lean foreground wins.

**PICTURE**
`docs/img/p1_parallax_video2_off.jpg` beside `_on.jpg`: the off image has the fence blocks, the on image loses them.

**CODE** `src/main/java/codec/encoder/MotionEstimator.java:310`
```java
consider(b, vx, vy, s, s + (long) lambda * (Math.abs(vx - predX) + Math.abs(vy - predY)));
```
- Cost = match error plus a penalty for disagreeing with the neighbours' median vector.
- It fixes bricks, but on a dark flat jacket it pulls the slow walker onto the camera's vector. That is part of the Stairs miss.

---

## Timing check
0:30 + 1:00 + 1:30 + 1:30 + 0:30 + 2:00 + 1:00 + 1:00 + 0:30 + 0:30 = 10:00. If you run long, cut slide 9 to one sentence and skip the slide 8 rate-control line.
