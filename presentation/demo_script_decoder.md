# Demo script: decoder and player (Window C, P3)

About 5 minutes. Written Nov 14 with the TEST files (fake checkerboard labels). After wire-up (P3.R),
swap in the real `myencoder` files (names below in brackets) and re-check every number marked (re-check).

## Before the audience arrives (do not skip)
1. Mac plugged in, other apps closed, no Claude window working, `caffeinate -dims` running in one tab.
2. In a second tab, with conda base active:
   ```
   cd ~/Documents/layer-codec
   ./gradlew -q jar
   ```
3. WARM-UP RUN, never shown: the first run after a build or a long idle always hiccups (Java compiles
   the hot code while it runs). Play it to the end, press q:
   ```
   ./mydecoder out/p3/SAL_2_5.cmp
   ```
4. Have the backup screen recording open in a player (P5), in case anything misbehaves live.

## Step 1. The command line and normal playback (about 40 s)
```
./mydecoder out/p3/SAL_2_5.cmp          (real: ./mydecoder data/SAL.cmp)
```
Let it play to the end (it pauses on the last frame). Point at the status line.
Say:
- "The decoder takes only the .cmp. The file's header carries the size, frame rate and frame count, so
  nothing else is needed."
- "It plays at the original 30 frames per second. The status line counts dropped and late frames
  honestly."
Numbers: full SAL clip plays in 14.58 s against 14.57 s, 0 dropped frames (RESULTS "Decoder and playback").

## Step 2. Side by side at n1 = 2, n2 = 5 (about 80 s)
```
./mydecoder data/SAL.cmp data/SAL.mp4
```
1. Let it play a second, press space to pause on a frame where the walker is clear.
2. Press s: decoded on the left, original on the right.
   Say: "Left is our output, right is the original video, read in step with it on its own thread."
3. Press z (1:1), then drag with the mouse to the walker and the brick wall.
   Say: "At 1:1 every pixel is drawn as it is. Scaling 1080p to a laptop screen hides exactly the block
   artifacts we want to compare, so both halves show the same spot, unscaled."
4. Press d (overlay).
   Say: "Red blocks are the ones the encoder marked foreground. The decoder knows this from one byte per
   block in the file, no segmentation code in the decoder. Foreground uses 2 to the 2, background 2 to
   the 5: the red blocks stay sharp, the others go blocky."
Numbers (re-check with real labels): foreground PSNR 47.7 dB vs background 34.5 dB at (2,5) (RESULTS "Compression").
Press q.

## Step 3. Swap the layers: n1 = 5, n2 = 2 (about 40 s)
```
./mydecoder data/SAL.cmp data/SAL.mp4      (real: encode with 5 2)
```
Pause on the same moment, press s, z, d, drag to the same spot.
Say: "Same frame, layers swapped: now the foreground is the blocky one and the background is clean.
The effect reverses exactly as the brief asks."
Numbers (re-check): foreground 34.5 dB, background 47.7 dB at (5,2).

## Step 4. Step back (about 30 s)
Still paused in the side-by-side 1:1 view: press the LEFT ARROW 5 times, then hold it for about 30 more.
Say: "Stepping back is instant: the last 30 frames are kept in memory, packed, 250 MB at 1080p. Further
back, the decoder jumps straight to the frame using the table of frame positions at the end of the file.
The original on the right steps back with it."
Press q.

## Step 5. Why it keeps up: the bench line (about 50 s)
```
./mydecoder out/p3/SAL_2_5.cmp --bench --workers 1,4
```
Say (point at the two lines):
- "One 1080p frame takes about 52 ms on one core, but at 30 frames per second we only have 33 ms."
- "So four worker threads each decode a whole frame at the same time, and numbered slots keep them in
  order. That gives about 61 frames per second, twice real time."
- "If a frame is ever late, the player skips to the right frame instead of slowing down, so the video
  always runs at its true speed."
Numbers (quiet run, test files, re-check): serial 52.3 ms; 4 workers 16.4 ms = 61.0 fps. Worst case
(0,0): 66 ms on one core, 46.1 fps with 4 workers, still 0 drops over the full clip.

## Step 6 (optional, if time). Extra credit and error handling (about 30 s)
```
./mydecoder out/p3/SAL_2_5_rle.cmp --bench --workers 4
./mydecoder data/SAL.mp4
```
Say:
- "Extra credit: zigzag plus run-length coding, mode 1 in the same file format. Same pictures, files
  about 20% smaller, and decoding is 25% faster because there is less data." Numbers: 76.2 vs 61.0 fps,
  1.23x smaller on SAL (2,5).
- (second command) "Give it the wrong file and it says why in one line, before any window opens."

## If something goes wrong
- Choppy start: "first run after a build; this is Java compiling the code" and play it again.
- Window too large: it is sized to fit the screen; do not maximize it (the picture keeps its size).
- Anything else: switch to the backup recording.

## Likely questions (two-sentence answers)
- Why drop a frame instead of slowing down? The brief asks for the original frame rate, and a slowed
  clip misrepresents the video. We count every drop on screen, and over a full clip we had none.
- Why 4 workers and not 8? The laptop has 4 fast cores and painting needs CPU too. A fair test (order
  alternated, cool-down first) showed 4 paints on time at least as well as 6.
- How do frames stay in order with several threads? Each finished frame goes into a slot numbered by
  frame. The player asks for exactly frame k and waits for that slot.
- How does the overlay know the foreground? Every 8x8 block in the file starts with a block_type byte,
  which the decoder reads anyway to pick n1 or n2.
