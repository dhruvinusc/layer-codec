# Demo script: encoder and segmentation (Window A)

About 5 minutes. Goes BEFORE the decoder demo (`demo_script_decoder.md`): this part makes the file, that part plays it.
All numbers are from the Mac unless marked sandbox. Sources: `docs/RESULTS.md` sections named in brackets.

## Before the audience arrives (do not skip)
1. Mac plugged in, other apps closed, no Claude window working, `caffeinate -dims` running in one tab.
2. In a second tab, with conda base active:
   ```
   cd ~/Documents/layer-codec
   ./gradlew -q jar
   ```
3. WARM-UP RUN, never shown. The first run after a build hiccups while Java compiles the hot code:
   ```
   ./myencoder data/video2.mp4 2 5
   ```
4. Make sure the four real files exist (`data/SAL.cmp`, `data/video1.cmp`, `data/video2.cmp`, `data/Stairs.cmp`). If not, encode them now: SAL takes about 2 minutes, never do that live.
5. Open the slide deck on the segmentation slides (see `slides_segmentation_notes.md`), and have the backup screen recording ready (P5).

## Step 1. The command line (about 45 s)
```
./myencoder data/video2.mp4 2 5
```
It runs for about 20 to 25 seconds. Talk over it.
Say:
- "This is exactly the brief's command: input video, n1 for the foreground, n2 for the background. It writes video2.cmp next to the input."
- "While it runs, it does two jobs per frame: first it decides which 16 by 16 blocks are moving objects, then it compresses each 8 by 8 block with 2 to the n1 or 2 to the n2."
When it prints the summary line, point at it:
- "Segmentation and compression time per frame, and the file size. The decoder needs nothing but this file."
Numbers: SAL, the full 1080p clip, encodes in 1 minute 51 seconds on this laptop, well inside the 10-minute limit [W2 / D13 Mac numbers].

## Step 2. How the encoder finds the moving objects (about 90 s, on slides)
Switch to the slides. Walk the pipeline slide in four sentences:
1. "For every 16 by 16 block I find where it came from in the previous frame. That offset is the motion vector. I search a small copy of the frame first, then refine, so a 30 pixel move costs very little."
2. "All four clips have a moving camera, so the background is not 'zero motion'. I fit the camera's own motion with a homography, using RANSAC so the walker's blocks are outvoted."
3. "What is left after removing the camera's motion is the evidence. A block is a candidate if it has real leftover motion and the camera's motion cannot explain it just as well."
4. "Neighbouring candidates join one object only if they move the same way. That is the brief's 'contiguous and consistent' rule. Then I fill holes inside objects and require a block to be foreground in two of three frames."

## Step 3. Before and after (about 60 s, on slides)
Show the naive slide, then the final slide (same three frames of SAL, then of video2).
Say:
- "Naive is the simplest rule: any textured block with leftover motion over 3 pixels. The walker is found, but the brick wall lights up, because bricks repeat and a block can match one brick over."
- "Final: same vectors, same camera model, plus the consistency rule and the camera test. On SAL false foreground drops from about 9% of the frame to about 1%, and the walker is found in all three frames."

## Step 4. Show the labels live in the decoder (about 40 s)
```
./mydecoder data/SAL.cmp data/SAL.mp4
```
Pause on the walker, press d.
Say: "The red blocks are the encoder's foreground decision, one byte per block in the file. They follow the walker across the clip."
Press q. (The decoder demo covers the side by side, the zoom and the layer swap.)

## Step 5. What it does not do, honestly (about 45 s, on slides)
Show the parallax pair, then the Stairs limit slide.
Say:
- "On video2 the camera runs alongside the joggers, so the fence close to the camera slides faster than the background. A flat camera model cannot explain that. I built a filter for it: it removes the fence, but it would also remove a person walking against the camera. I kept it off, because a blurred person is a worse mistake than a few extra sharp blocks."
- "At the top of the Stairs clip the walker is far away and moves about 2 pixels a frame against the camera, which is at the limit of block matching. I tried adding up his motion over several frames; it found almost nothing and added false patches on video2, so I measured it, wrote it down and took it out."

## If something goes wrong
- The encoder errors on a missing input: show the usage line; it validates arguments first.
- Encoding takes much longer than 25 s: something else is using the CPU. Say so, and use the pre-encoded `data/video2.cmp`.
- Any crash: switch to the backup recording, keep talking from the slides.

## Likely questions (short answers)
- Why 16 by 16 blocks? The brief asks for macroblocks of 16 by 16; the 8 by 8 DCT blocks inherit their macroblock's label.
- Why only the Y channel for motion? Brightness carries the structure; colour adds cost and little information for matching (the brief allows Y or R).
- Is it really motion only? Yes: inputs are vectors, match errors and the camera model. Texture only decides whether a vector is trusted.
- Is it deterministic? Same machine, same input: byte-identical labels (two runs, same SHA-256). A different FFmpeg build decodes slightly different pixels, so labels can differ by about 0.1% of blocks across machines.
- Why is the .cmp much bigger than the mp4? The baseline format stores every frame independently with no prediction between frames, as the brief allows. The extra-credit modes address that.
