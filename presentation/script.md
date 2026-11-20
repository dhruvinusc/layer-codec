# Talk script: Video Layer Based Compression (Dec 2, 10 minutes)

How to use this file:
- **SAY** is what you speak. At most two numbers per slide, and each number is followed by what it means.
- **IF HE ASKS** gives a plain meaning for every technical word on the slide. Learn these first.
- **WHY WE CHOSE IT** is the decision behind the slide, in one or two lines.
- **ON THE SLIDE** describes a simple picture. The slide tool can make it look nicer later.
- Code is not shown on slides. Tags like **[J5]** mark every job; look the number up in `presentation/code_glossary.md` (file, line, one sentence to say, test).
- All numbers are Mac numbers from `docs/RESULTS.md`, the report draft, or the lab story.

Time in brackets: how long the slide takes / clock time at the end of the slide.

---

## Slide 1. The problem (0:30 / 0:30)

**SAY**
"When we watch a video, our eyes follow whatever is moving. But a normal compressor spends the same effort on every part of the picture. The brief asks for a smarter split: find the moving things using motion alone, keep them sharp, and compress everything else harder. My question for this talk is simple: where do the bits go, and what do we gain?"

**ON THE SLIDE**
One SAL frame. The walker is outlined. Two labels: "sharp" on the walker, "rougher" on the wall.

**IF HE ASKS**
- *Bits:* the size of the file. Fewer bits means a smaller file and usually a rougher picture.

---

## Slide 2. How it works, in five steps (1:00 / 1:30)

**SAY**
"The encoder does five things. One: read each frame of the mp4. **[J1]** Two: for every small square of the picture, find where it was in the previous frame. That gives an arrow per square, called a motion vector. **[J5, J7]** Three: figure out which arrows are just the camera moving and which belong to something that moves on its own. Those squares are the foreground. **[J21]** Four: compress each square, finely if it is foreground, coarsely if it is background. **[J27]** Five: write everything into one file. **[J28 to J30]** The decoder reads only that file and plays it back. **[J31]**"

**ON THE SLIDE** (one straight row, five boxes, one arrow between each pair, nothing else)
```
[ Read frames ] -> [ Find motion ] -> [ Label: moving or not ] -> [ Compress ] -> [ .cmp file ] -> [ Player ]
```
Under boxes 2 and 3: "uses brightness only". Under box 4: "fine for moving, coarse for the rest".

**WHY WE CHOSE IT**
- Java for everything; FFmpeg only to open the mp4. So every graded step is my own code.
- Brightness only for finding motion, because colour adds little there and costs three times the work. Compression still uses full colour (R, G, B), as the brief says.

**IF HE ASKS**
- *Motion vector:* an arrow saying "this 16 by 16 square was here in the last frame".
- *Why 16 by 16 squares for motion, 8 by 8 for compression:* the brief sets both. Each 8 by 8 square takes the label of the 16 by 16 square it sits in.
- *Padding:* 1080 is not divisible by 16, so I copy the last row 8 more times to get 1088, and cut it off again when playing.

---

## Slide 3. Finding the moving things (1:30 / 3:00)

**SAY**
"In all four clips the camera itself moves. So I cannot say 'background is whatever stands still', because nothing stands still. Instead: background is whatever moves the way the camera makes it move.
First I work out the camera's motion from all the arrows together. The people are a small part of the picture, so they are outvoted. **[J10, J11]**
Then, for each square, I subtract the camera's motion from its arrow. If almost nothing is left, it is background. If a lot is left, it might be foreground. **[J13, J14]**
Last, a square only counts as foreground if its neighbours have leftover motion in the same direction **[J15, J16]**, and it stays that way for at least two of three frames **[J19]**. That kills random noise.
The result: a simple rule on the brick wall marks about 9 squares in every 100 as moving **[J23]**. Mine marks about 1 in 100, and that 1 is the walker."

**ON THE SLIDE**
Two pictures side by side, same SAL frame: left "simple rule", right "my rule".
- Left: big coloured blobs on the brick wall.
- Right: the wall is clean, only the walker is coloured.
One line under it: "Same arrows, better rule: 9 in 100 squares to 1 in 100."

**WHY THE LEFT PICTURE LOOKS WRONG**
Bricks repeat every few pixels. A square of bricks can match "one brick over" almost as well as its true spot, so it gets a wrong arrow. A wrong arrow disagrees with the camera, so the simple rule calls it moving. My rule asks two more questions: "does the camera's own arrow fit almost as well?" and "do the neighbours agree?" Wrong brick arrows fail both.

**WHY WE CHOSE IT**
- Camera model that handles pan, tilt and perspective, instead of the brief's "one constant arrow for the whole frame". On Stairs one constant arrow explains roughly two thirds of the squares; my model explains about 9 in 10.
- When unsure, call it foreground. A wrongly blurred person is what a viewer notices; a wrongly sharp wall only costs a bit of file size.

**IF HE ASKS**
- *Homography:* the camera model. Picture the background printed on a flat sheet. If you slide, turn, tilt or zoom that sheet, every point moves in a predictable way. A homography is the rule, 8 numbers, that describes that movement for every point at once. It is exact for flat things like the wall and the stairs. It is not exact when the scene has depth, which is my main source of errors.
- *RANSAC (how the camera's motion is found):* pick 4 random squares, build a camera rule from them, count how many other squares agree. Repeat 500 times, keep the rule most squares agree with. Moving people never get the majority. The random seed is fixed, so the result is the same every run.
- *Leftover motion (residual):* the square's arrow minus the camera's arrow at that spot. Like walking on a moving train: your speed minus the train's speed is what you did yourself.
- *The 2.5 pixel threshold:* leftover motion below 2.5 pixels is treated as camera error. Picked by looking at all four clips together, never one clip at a time.
- *"9 in 100 to 1 in 100", exactly:* 8.68% to 1.07% of all squares in the whole SAL clip. On video2 it goes from about 1 in 6 (16.56%) to about 1 in 14 (7.25%). Both rules use the same arrows, so only the rule changed.

---

## Slide 4. Compressing a square (1:15 / 4:15)

**SAY**
"Each 8 by 8 square is first rewritten as a mix of 64 standard patterns, from smooth to busy. This step is called the DCT **[J25]**, and it loses nothing: I can turn it back into the exact pixels.
The loss comes next. Every pattern weight is divided by a step size and rounded **[J26]**. A small step keeps detail. A big step turns most of the small weights into zero, which saves space but blurs the square.
The brief says the step must be a power of two. So n1 sets the step for moving squares and n2 for the background **[J24, J27]**. In my main setting the moving squares get a step of 4 and the background a step of 32."

**ON THE SLIDE**
One real square from the walker, three panels left to right:
"original" -> "step 4: looks the same" -> "step 32: blurry, mostly zeros".
Source: `docs/img/dct_SAL_f150.png` (crop just one row of it; the full image is too busy for a slide).

**WHY IT LOOKS THAT WAY**
Most of a natural picture is smooth, so most of the weight sits in the few smooth patterns. The busy patterns have small weights, and at step 32 they round to zero. With step 4, 19 of 64 weights become zero; with step 32, 53 of 64 do.

**WHY WE CHOSE IT**
- Round to the nearest value instead of chopping the decimals: it halves the average error.
- One step for all 64 weights, because the brief requires it, even though JPEG uses a smarter table.

**IF HE ASKS**
- *DCT:* rewriting 64 pixels as 64 pattern weights. Like describing a chord by its notes instead of by the sound wave.
- *Quantization:* the divide-and-round step. The only place information is lost.
- *2^n:* the step size. n = 2 means step 4, n = 5 means step 32.
- *DC:* the weight of the flat pattern, so the average brightness. For a flat square of value 200 it is 576. (Subtract 128 to centre the values, get 72; the formula multiplies the average by 8.)

---

## Slide 5. The file and the player (0:30 / 4:45)

**SAY**
"The file stores the two step sizes, then for every square a single byte saying moving or not, and its weights for red, green and blue **[J28]**. That is exactly the brief's order. I added a small header with the picture size and frame rate, so the player needs nothing but this file **[J29, J32]**. To play 1080p smoothly, four threads decode frames at the same time **[J37]**, and a clock shows each one exactly on time **[J38]**."

**ON THE SLIDE**
A short strip: `[ header ] [ frame 1 ] [ frame 2 ] ...` and under one frame: `[ moving? ] [ 64 red ] [ 64 green ] [ 64 blue ]`.

**WHY WE CHOSE IT**
- Binary instead of the text format suggested in the brief. Same content, same order, but text has no place for size or frame rate and would be gigantic. A tool can print any frame in the exact text layout.
- Several frames at once, because one 1080p frame takes about 54 ms on one core and we only have 33 ms per frame.

**IF HE ASKS**
- *zlib:* ordinary zip compression on each frame. It changes no pixel, it only packs the zeros tightly.
- *What if a frame is late?* It is skipped and counted, so the video keeps the original speed. In every real run nothing was skipped.

---

## Slide 6. LIVE DEMO (2:00 / 6:45)

**>>> DEMO STARTS. Switch to Terminal. The menu (`bash demo/demo.sh`) is open; items 0 and 1 were run before the talk.**

| Clock | Press | Say |
|---|---|---|
| 4:45 | **menu 2** [J0] | "This is the brief's exact command: myencoder, video2, 2 for moving, 5 for background." While it runs: "It is finding motion, the camera, the labels, and compressing. About 20 seconds." |
| 5:10 | read the `encoded` line | "Done, well under the 10 minute limit." |
| 5:15 | **menu 4** [J31] | "The player gets only the compressed file. I also give it the original, to compare." |
| 5:20 | `space` [J38] | "It plays at the original speed, 30 frames per second." |
| 5:30 | `space`, `right`, `right`, `left` [J39, J40] | "Pause. Step forward. Step back." |
| 5:40 | `s` [J41] | "Left is compressed, right is the original." |
| 5:50 | `d` [J43] | "The tinted squares are the ones marked as moving. The player knows this from the file itself." |
| 6:00 | `z`, drag onto a jogger [J42] | "Zoomed to real pixels: the jogger is clean, the trees are blocky. That is the whole idea." |
| 6:25 | `q`, read the `play` line | "No frames skipped." |
| 6:35 | back to slides | |

If anything fails: "Here is the same run recorded." Play the backup video. Do not debug live (`demo/DEBUG.md`).

**WHY VIDEO2 FOR THE DEMO**
It compresses in about 20 seconds, so it can run live. The big clips are prepared in advance.

**<<< DEMO ENDS.**

---

## Slide 7. The proof: swap the two steps (1:00 / 7:45)

**SAY**
"The brief says the effect must flip when the two step sizes are swapped. Here is the same SAL frame three ways. Original. Then moving squares fine, background coarse: the wall is blocky, the walker is clean. Then swapped: now the walker is blocky and the wall is clean. Same video, same labels; only the step sizes moved **[J34]**."

**ON THE SLIDE**
`docs/img/p3r_SAL_swap_crop_x3.png`, three crops labelled "original", "walker fine", "walker coarse".
One line underneath: "Quality score: walker 47.5 -> 34.4, wall 34.3 -> 47.7 (higher is better)."

**WHY WE CHOSE IT**
Quality is measured separately for moving and still squares. One overall score would hide the effect, because the walker is only about 1% of the picture.

**IF HE ASKS**
- *PSNR, the quality score:* how close the compressed picture is to the original, in decibels. Higher is closer. Every 6 dB is roughly half the error. Above 40 looks almost perfect; around 34 you start to see blocks.
- *Does it cost the same both ways?* No. The background is about 99% of the squares, so giving the background the fine step makes the SAL file 2.7 times bigger.

---

## Slide 7b. Field clips: my own videos (0:45 / 8:30)

**SAY**
"I filmed five clips of my own on my phone to test it beyond the course videos: traffic, shoppers, a friend on a scooter. Same code, same settings, nothing tuned. All five played with no dropped frames.
The win: the scooter is found even while I pan to follow him, because his motion disagrees with the camera and agrees with itself.
The miss: a car driving straight at me. It does not slide, it grows, so its squares barely move sideways and my method does not see it. That is the limit of one arrow per square."

**ON THE SLIDE**
Two frames side by side, each with the moving squares tinted:
- Left, "Found": the scooter rider while the camera pans.
- Right, "Missed": the head-on car, mostly untinted.
Under them, a 5-row table: clip, frames, .cmp size at (2,5), dropped frames (0 for all five). One line: "5 phone clips, same code, same settings: 0 frames dropped."

**WHY THE PICTURES LOOK THAT WAY**
- Scooter: the camera's motion is removed first, so the background goes quiet and the rider's leftover motion stands out and points one way.
- Head-on car: an object coming toward you grows from its centre. The middle squares hardly move at all, and the edge squares move outward in opposite directions. Small motion fails the "big leftover" test, and opposite directions fail the "neighbours agree" test.

**WHY WE CHOSE IT**
Test beyond the clips I tuned on, without retuning, so the result says something about the method rather than about the settings. Kept out of the official results because the brief's test set is the four course clips.

**IF HE ASKS**
- *What clips:* five iPhone videos, 1080 by 1920 portrait, the same pixel count as the 1080p course clips: cars with a still camera, cars both ways, shoppers indoors, a scooter followed by the camera, a fast scooter.
- *Numbers:* all five played with 0 dropped frames and within 0.17% of the true clip length; encoding took 40 to 149 seconds per clip, under the 10 minute limit. One clip is 29.97 frames per second, and the player kept that exact rate.
- *Other misses:* a black shirt against black baskets (no texture, so no reliable arrow), two people walking side by side merge into one region (same motion, touching), and poles close to the camera while panning (parallax, same as the video2 fence).
- *Did you change anything for them?* No. Same frozen code, same thresholds.

---

## Slide 8. Does the uneven split actually pay? (0:45 / 9:15)

**SAY**
"After the code was finished I tested the question directly: for the same file size, is it better to split unevenly or evenly? The grey line is the even split. Both parts get the same step, so to make the file smaller the walker has to get worse too. The orange line keeps the walker's step fixed and only makes the background rougher **[J50]**. The walker stays sharp while the file shrinks."

**ON THE SLIDE**
`docs/img/rd_video2_even_vs_uneven.png`, but only the first of its three panels (the other two show the same shape).
One line: "Uneven: walker much sharper, file 41% smaller."

**WHY THE CHART LOOKS THAT WAY**
The orange line is flat because the walker's step never changes, so its quality never changes. Moving left along it, only the background gets rougher, so the file gets smaller. The grey line drops because with an even split, a smaller file means a worse walker too.

**WHY WE CHOSE IT**
For streaming: keep the walker's step fixed, then pick the roughest background that still fits the internet speed you have. All lab tests run on the finished code without changing it.

**IF HE ASKS**
- *The exact pair:* even split (4,4) is 31.2 MB with the walker at 38.6 dB. Uneven (2,6) is 18.5 MB with the walker at 47.7 dB. So 9.2 dB better for 41% fewer bytes. The background drops from 38.8 to 30.4 dB; that is the price.
- *Your file sizes are huge compared to real streaming.* Yes: my codec stores most frames whole and has no advanced packing, so it cannot compete with H.264 on size. The point is the split: whatever the budget, the walker stays sharp and the background absorbs the cut.

---

## Slide 9. Extra credit (0:15 / 9:30)

**SAY**
"Two extras, off by default: packing the zeros **[J44, J45]** makes the file a third smaller with identical pixels, and storing every second frame as a difference **[J46]** makes it about half the size."

**ON THE SLIDE**
`docs/img/sizes_4clips.png`: three bars per clip, normal, extra 1, extra 2.

**IF HE ASKS**
- *Exact numbers:* extra 1 is 29 to 40% smaller with identical pixels. Extra 2 is 36 to 51% smaller and loses at most 0.24 dB.
- *Why only every second frame?* Each predicted frame loses a little to rounding, and the error builds up. I tested longer groups; they were both bigger and worse.
- *How do you know the normal file did not change?* A test compares the normal output to a saved copy, byte for byte.
- *Zigzag:* reading the 64 weights from smooth to busy, so all the zeros end up together at the end and can be stored as one count.

---

## Slide 10. Honest limits (0:30 / 10:00)

**SAY**
"Three things do not work perfectly. On Stairs, a walker far away moves only about 2 pixels against the camera, which is too close to the camera model's own error, so in about a fifth of the frames he is missed **[J8]**. On video2, the fence close to the camera slides faster than the far trees, and the camera model cannot explain both, so the fence sometimes gets marked as moving. I built a filter for that **[J22]** but kept it off, because it also removed a real person. And one lab idea simply failed, so I did not build it. Thank you."

**ON THE SLIDE**
Three short lines with a small picture each:
- "Far, slow walker" (Stairs frame)
- "Near fence slides past" (`docs/img/p1_parallax_video2_off.jpg`)
- "One idea that failed" (no picture needed)

**IF HE ASKS**
- *Parallax:* close things move across the picture faster than far things when the camera moves. Hold a finger up and move your head: the finger jumps, the wall behind barely moves. One flat camera model cannot capture both at once.
- *About a fifth, exactly:* 74 of 346 Stairs frames.
- *The failed idea:* use the camera model alone to predict the whole background, instead of one arrow per square. It worked on SAL, where the wall is flat, and was worse on the other three clips, which have depth. I set the pass bar before measuring, it failed, so I stopped.

---

**CLOSING SLIDE:** "Questions" (dark, after the hidden E1 backup). It stays up during Q&A and takes no talk time.

**BACKUP SLIDE AFTER THIS ONE (hidden):** `lab/viz/E1_backup_slide.png`, the parallax filter off vs on with the Mac table (video2 7.26% to 4.29%, no effect on SAL or Stairs). Show it only if he asks about the fence or the filter.

---

## Code
Every [J] tag above is in `presentation/code_glossary.md`: file, line, one sentence to say, and the test that proves it. The glossary also has a slide-to-job table for Q&A.

## Timing check
0:30 + 1:00 + 1:30 + 1:15 + 0:30 + 2:00 + 1:00 + 0:45 (field clips) + 0:45 + 0:15 + 0:30 = 10:00. If you run long, drop the "IF HE ASKS" detail from slide 7b and keep only the win and the miss.
