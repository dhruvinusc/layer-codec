# P3 Decoder and player: decision log

Owner: Window C. Oldest first. Matching technical entries: repo docs/DECISIONS.md P3-D1 to P3-D3.

### P3-L1: Short test files for the player
- Date / window: Nov 11, Window C
- Brief says: Brief is silent.
- The problem in plain words: The player had to be built before the real encoder existed, and the Mac had only about 14 GB of free disk. A full-length SAL file at n = 0 is about 2 GB.
- Options we considered:
  1. Full-length test files for every setting : good tests whole clips / bad 6 GB or more, risks filling the disk
  2. Short files (video2 first 120 frames, SAL first 60) at (2,5), (5,2), (0,0), (1,7) : good under 1 GB total, covers typical and worst cases / bad cannot prove full-length playback timing yet
- What we chose: Short files from CodecHarness with fake checkerboard labels, in out/p3/.
- Why (plain words): Enough frames to test speed, order and stepping, without risking the disk. Full-length timing is proven later on real encoder output.
- Proof: out/p3/*.cmp; PSNR matches the Mac P2.9 run exactly (video2 (2,5) foreground 48.14 dB, background 34.39 dB). WORKLOG [C/P3] Nov 11.
- Cost or limitation: Playback duration on whole clips (P3.7) needs longer files; free disk space first.
- Say it in the presentation: "We built the player on short test files first, then re-measured on full clips from the real encoder."
- Likely professor question: How do you know it works on whole clips? / Answer: The short files test every mechanism. The final timing table in RESULTS comes from full clips encoded by myencoder.

### P3-L2: Decode several whole frames at once
- Date / window: Nov 12, Window C (Dhruvin's check led to this)
- Brief says: Play at the original frame rate (D4).
- The problem in plain words: At 30 fps each frame has 33.3 ms. One 1080p frame takes 71.9 ms on one core at n = 0, and 45.6 ms even when all 8 cores share it. So decoding one frame at a time can never keep up.
- Options we considered:
  1. Split one frame across all cores : good simple ordering / bad 45.6 ms is still over budget, and zlib unpacking cannot be split
  2. Several workers, each decoding a whole different frame on one core : good about W frames per frame-time, unpacking runs in parallel too / bad frames finish out of order, more memory
  3. Lower quality or skip frames : good easy / bad changes what the grader sees
- What we chose: Option 2, with max(2, cores minus 2) workers (6 on the Mac). Each worker uses the plain single-core decode, never the split version, so 6 workers use exactly 6 cores.
- Why (plain words): It is the only option that meets 30 fps in the worst case without changing the picture. Leaving 2 cores free keeps the display and the system responsive.
- Proof: RESULTS P3.1 (Mac): SAL n = 0 goes from 72.1 ms per frame (1 worker) to 21.0 ms (6 workers) = 47.7 fps, 1.59x real time. At (2,5) 4 workers give 60.7 fps. DecodePipelineTest everyFrameIdenticalToSerialDecode.
- Cost or limitation: Gains flatten after 4 workers (likely 4 fast and 4 slow cores plus memory speed). More memory in use (see P3-L3).
- Say it in the presentation: "One frame takes 72 ms, more than twice our 33 ms budget, yet we play at 47 fps because six frames decode at the same time."
- Likely professor question: Why does one frame take longer than 33 ms but the player keeps up? / Answer: Speed is about throughput, not one frame's time. Six workers each finish a frame every 72 ms or so, which is one finished frame about every 21 ms on average.

### P3-L3: Keeping frames in order with bounded memory
- Date / window: Nov 12, Window C
- Brief says: Brief is silent.
- The problem in plain words: With several workers, frame 12 can finish before frame 10. Also, fast workers could race ahead and fill memory: one 1080p frame is about 25 MB.
- Options we considered:
  1. A simple first-come queue : good easy / bad frames come out in the wrong order
  2. Numbered slots plus a window (workers may only decode frames k to k + 7, where k is the frame the player wants next) : good always the right frame, memory capped / bad a step back by one restarts the window
  3. Unlimited buffer : good never waits / bad runs out of memory at 1080p
- What we chose: Option 2. The window size is what fits in a quarter of the memory limit, kept between 2 and max(8, workers): 8 frames, about 200 MB, at 1080p. A request outside the window restarts decoding there and throws away old work (a "generation" number marks stale work).
- Why (plain words): The player asks for frame k and gets exactly frame k. Memory never grows past the window plus one frame in progress per worker.
- Proof: DecodePipelineTest framesComeOutInOrderUnderRandomWorkerDelays, bufferNeverExceedsCapacity, seekBackAndJumpAheadReturnTheRightImage, closeStopsEveryThread.
- Cost or limitation: Stepping back restarts decoding; the frame cache (P3.2) will make short steps back instant.
- Say it in the presentation: "Each frame has a numbered slot, so order is guaranteed, and workers can only run eight frames ahead, so memory stays near 200 MB."
- Likely professor question: How do frames stay in order? / Answer: Finished frames are stored by frame number, not by arrival. The display asks for number k and waits until that exact slot is filled.

### P3-L4: A hidden timing mode built early
- Date / window: Nov 12, Window C (asked for by Dhruvin)
- Brief says: Play at the original rate (D4); the whole run must finish within 10 minutes (E4).
- The problem in plain words: We needed proof that the decoder is fast enough at the worst setting before building the player on top of it.
- Options we considered:
  1. Wait for the player and measure playback : good real conditions / bad problems found late
  2. Add a hidden `--bench` flag now that decodes every frame without a window and prints speed : good early, repeatable proof / bad one more code path
- What we chose: `mydecoder input.cmp --bench [--workers 1,2,6]`. Off by default, so the required command line is unchanged.
- Why (plain words): It answered the key risk (can we hold 30 fps at n = 0?) on day one of P3, with numbers from the Mac.
- Proof: RESULTS P3.1; DecoderMainTest benchFlagParsed.
- Cost or limitation: Measures decoding only, not painting; real playback is measured in P3.3.
- Say it in the presentation: "Before building the player, we measured the decoder alone: 47.7 fps at the worst setting."
- Likely professor question: Does `--bench` change normal behavior? / Answer: No. It only runs when the flag is given, and `mydecoder input.cmp` works exactly as the brief specifies.

### P3-L5: Six decode workers by default
- Date / window: Nov 12, Window C
- Brief says: Play at the original frame rate (D4). Worker count is not mentioned.
- The problem in plain words: More workers decode faster, but the Mac has 8 cores and the display and system also need some. Too many workers just fight over the same cores.
- Options we considered:
  1. 4 workers : good as fast as 6 at (2,5), leaves more room for the display / bad 17% slower at n = 0, the worst case
  2. 6 workers (cores minus 2) : good fastest at n = 0, still leaves 2 cores for display and system / bad no gain at typical settings
  3. 8 workers (all cores) : good maximum decode power / bad the display thread would compete and frames could be painted late
- What we chose: max(2, cores minus 2), which is 6 on the Mac. `--bench --workers` can test any other count.
- Why (plain words): The default must survive the worst case, and n = 0 is where every millisecond counts. At typical settings 6 is no slower than 4, so it costs nothing there.
- Proof: RESULTS P3.1 (Mac, SAL 1080p, ms per frame): n = 0: 1 worker 72.1, 4 workers 24.6, 6 workers 21.0 (47.7 fps). (2,5): 1 worker 53.9, 4 workers 16.5, 6 workers 17.0.
- Cost or limitation: Speed flattens after 4 workers. The MacBook Air likely has 4 performance and 4 efficiency (slower, power-saving) cores, and all workers share the same memory bandwidth (each 1080p frame is about 25 MB). Late-frame counts with the real player (P3.3) and long runs on a fanless laptop (P3.7) may still favor 4; we will re-check there.
- Say it in the presentation: "Six workers is the fastest setting at the hardest quality level, and going past four mostly hits the limits of the laptop's slower cores and memory."
- Likely professor question: Why not use all 8 cores? / Answer: The display thread must paint a frame every 33 ms and the system needs a core too. Our measurements show 4 to 6 workers already exceed real time, so taking the last two cores would only risk late paints.

### P3-L6: A 30-frame cache for stepping back
- Date / window: Nov 12, Window C
- Brief says: Play, pause and step frame by frame (E2). Stepping backwards is not mentioned.
- The problem in plain words: The decoder only runs forward. Without a cache, one press of the left arrow throws away the work in progress and waits 54 to 72 ms for a fresh decode of one 1080p frame.
- Options we considered:
  1. No cache, always decode again : good no memory / bad every step back waits and wastes work
  2. Keep the last 30 frames as three number planes (red, green, blue) : good no conversion / bad about 750 MB at 1080p
  3. Keep the last 30 frames packed (red, green and blue squeezed into one number per pixel) : good about 250 MB, and it is the form the screen paints / bad a small pack and unpack step
  4. Keep the whole clip : good any step is instant / bad SAL would need 3.6 GB packed
- What we chose: Option 3. A ring of the last 30 frames (one second of video), packed. The oldest frame leaves first and its memory is reused. Anything older is decoded again straight from the file, using the footer table of frame positions.
- Why (plain words): One second of instant step-back covers normal inspection, and 250 MB fits next to the 200 MB decode window inside the 4 GB memory limit. Going further back still works, it just costs one decode per step.
- Proof: FrameCacheTest stepBackFiftyFrom100CrossesTheCacheBoundaryCorrectly: after playing to frame 100 the ring holds 71 to 100; stepping back to 50 gives 29 cache hits and 21 re-decodes, and every frame matches a fresh decode pixel for pixel. memoryFigureFor30Frames1080p: 249.8 MB.
- Cost or limitation: Each step back past the ring restarts the decode window, which decodes up to 8 frames to use one. Fine for occasional long jumps; we will check how it feels in the player (P3.3).
- Say it in the presentation: "The last second of video is kept in memory, so stepping back is instant; further back, we jump straight to that frame in the file using the frame table."
- Likely professor question: How does step back work beyond the cache? / Answer: The file's footer lists where every frame starts, so the decoder reads just that frame and decodes it. It never rereads the file from the beginning.

### P3-L7: What the player does when a frame is late
- Date / window: Nov 12, Window C
- Brief says: The decoder plays at the ORIGINAL frame rate (D4), with play, pause and step (E2).
- The problem in plain words: Sometimes a frame is not ready by its deadline (a slow frame, the laptop busy). The player must decide whether to wait, slow down, or skip.
- Options we considered:
  1. Wait for every frame and slow the video down : good never skips a picture / bad the clip plays longer than it really is, which breaks "original frame rate"
  2. Sleep a fixed 33 ms after each frame : good simple / bad every small delay adds up, so the video drifts later and later
  3. Deadlines from the frame number, and when behind, jump to the newest frame that is already due : good keeps true speed, never drifts / bad an occasional skipped picture
- What we chose: Option 3. Frame i is due at start time + i x 33.3 ms. If the player falls behind, it skips straight to the frame that should be on screen now and counts the skipped ones as "dropped". A frame painted more than half a frame (16.7 ms) after its deadline counts as "late". After a pause the clock restarts, so a pause is never counted as falling behind.
- Why (plain words): Viewers notice a clip running slow more than one missing frame, and the brief asks for the original rate. Counting drops and late frames on screen and in the report makes the cost visible instead of hidden.
- Proof: PlayerTest (5 tests on a fake clock): with 2 ms decoding all 90 frames play on time (3.00 s); with 50 ms decoding about a third are dropped but the 120-frame clip still takes 4.0 s; a 10-second pause causes no drops. Mac numbers: RESULTS P3.3.
- Cost or limitation: If decoding were always slower than 33 ms, the video would look choppy (but still on time). Our pipeline decodes faster than that (RESULTS P3.1), so drops should be rare.
- Say it in the presentation: "The player never slows the video down: if a frame is late it skips ahead to the right one and counts it, so you can see exactly how often that happens."
- Likely professor question: Why drop a frame instead of slowing down? / Answer: The brief asks for the original frame rate, and a slowed clip misrepresents the video. Dropping keeps time correct, and our counter shows it almost never happens.

### P3-L8: Filling a small buffer before the clock starts
- Date / window: Nov 12, Window C (found in Mac run 1)
- Brief says: Play at the original frame rate (D4).
- The problem in plain words: On the first Mac run every clip "played" 9 to 17% too short, with all drops at the start. The clock started before the first picture was on screen, so the half second spent opening the window and decoding the first frame (code that has not warmed up yet) looked like the player was already 19 frames behind.
- Options we considered:
  1. Start the clock immediately : good simplest / bad the start-up time is counted as lag and frames are skipped
  2. Start the clock when frame 0 appears : good removes most of it / bad the next frames may still not be decoded yet
  3. Pre-roll: show frame 0, wait until the next 6 frames are decoded (at most 1 second), then start : good what real video players do with their buffer / bad playback begins up to 1 second later
- What we chose: Option 3.
- Why (plain words): The viewer sees frame 0 straight away, and the clock only starts once the decoder is ahead. The duration is then measured honestly, from the moment playback really starts.
- Proof: Mac run 1 numbers in RESULTS P3.3 (video2: 19 drops, 3.33 s instead of 4.00 s). PlayerTest slowStartIsAbsorbedByPrerollNotCountedAsDrops: a 0.6 s cold start now gives 0 drops and exactly 4.00 s. Mac run 2 numbers to follow.
- Cost or limitation: Up to 1 second of waiting on frame 0 before motion starts.
- Say it in the presentation: "Our first measurement showed clips finishing early; the cause was start-up time counted as lag, so like any video player we now fill a small buffer before starting the clock."
- Likely professor question: Isn't the pre-roll hiding a slow decoder? / Answer: No. It only covers the one-time start. The report counts every late and dropped frame after that, and the last 100 frames had zero drops even before the fix.

### P3-L9: Changing the default from 6 workers to 4
- Date / window: Nov 12, Window C (replaces the default chosen in P3-L5)
- Brief says: Play at the original frame rate (D4).
- The problem in plain words: P3-L5 picked 6 workers because they decode fastest. But the real player also has to scale and paint every frame, and on the Mac that work shares the same cores. With 6 workers busy, paints were often late.
- Options we considered:
  1. Keep 6 workers : good fastest decoding (47.7 fps at the hardest setting) / bad 27 to 45% of 1080p frames painted late
  2. 4 workers : good 8 to 12% late, zero drops, still decodes 1.4x to 2x faster than real time / bad less spare decode speed at the hardest setting
  3. Make painting itself cheaper first : good could help both / bad more code for a gain we can already get by choosing 4
- What we chose: 4 workers on the Mac. The rule is half the cores, at least 2, and never more than cores minus 2.
- Why (plain words): What the grader sees is the painted picture, not the decoder's speed. Measured in the real player, 4 workers paint on time far more often, and they still decode faster than the video plays.
- Proof: RESULTS P3.3 run 4, the fair test (cool-down first, order 4, 6, 6, 4, 200 frames of SAL): 4 workers averaged 3.5% late frames and 0 to 1 late in the second half; 6 workers averaged 5.75% and 2 to 8 in the second half; zero drops and correct duration (within 0.13%) for both. Earlier runs 1 to 3 pointed the same way but were not fair, because the first run of each pair was always worse. Bench: 4 workers decode at 57 to 61 fps at (2,5) and 40.7 fps at (0,0).
- Cost or limitation: At the hardest setting the decoder has less spare speed (1.36x real time instead of 1.59x). The full-length thermal test (P3.7) will show if that is enough over a whole clip. A lesson we kept: our first three comparisons were unfair because run order mattered more than the setting, so the final test alternated the order and cooled the laptop first.
- Say it in the presentation: "We first picked six workers because they decode fastest, but measuring the real player showed four paint on time far more often, so we changed the default."
- Likely professor question: Why would fewer threads be better? / Answer: The laptop has only four fast cores, and painting needs CPU too. Past four workers, extra decoders just compete with the painter, so frames arrive on time but get painted late.

### P3-L10: Recovering from lost work (Nov 12 incident)
- Date / window: Nov 12 17:28 incident, recovered Nov 13, Window C
- Brief says: Brief is silent. Project rule: one chat per window role, commit at every green checkpoint.
- The problem in plain words: A second Window C chat was opened by mistake. While both were active, the uncommitted start of P3.4 (the new view code in Display.java and its test file) was thrown away to stop the two chats colliding on the same file. All committed work was safe.
- Options we considered:
  1. Leave both chats running and hope they touch different files : good no pause / bad both needed Display.java, the exact file that collided
  2. Stop one chat, discard its uncommitted changes, redo the work from the surviving chat's own record : good one clear owner, nothing half-merged / bad an hour of rework
  3. Try to merge the two chats' uncommitted edits : good keeps everything / bad nobody could tell whose lines were whose
- What we chose: Option 2. The PM confirmed this chat as the only Window C, and the P3.4 code was rebuilt from this chat's own record of what it had written, then tested and committed before going further.
- Why (plain words): Clear ownership is worth more than an hour. The rebuilt code went through the same tests, plus a check on real SAL frames.
- Proof: commit for P3.4 (WORKLOG Nov 13); DisplayViewTest and OriginalVideoTest green; images docs/img/p3_SAL_*.
- Cost or limitation: About an hour lost. The rule "one chat per window role" is now in the master to-do.
- Say it in the presentation: "When two chats briefly worked on the same file, we kept one owner, threw away the half-finished copy and rebuilt it from the record, rather than guessing at a merge."
- Likely professor question: How do you avoid losing work with several assistants? / Answer: Each window owns its own files, commits at every green test run, and only one chat may play a role at a time. The one loss we had was uncommitted work, which is why we now commit sooner.

### P3-L11: Comparing with the original: side by side and 1:1 zoom
- Date / window: Nov 13, Window C
- Brief says: Own display code; output can be compared against the original (E1, E3). Foreground visibly cleaner than background when n1 < n2 (D5).
- The problem in plain words: To judge quality, the grader must see our decoded frame next to the original. A 1080p frame squeezed onto a laptop screen hides the blocky artifacts that show the layer effect.
- Options we considered:
  1. Two separate windows : good simple / bad hard to line up, two things to arrange during a demo
  2. One window, decoded left and original right, each scaled to fit : good easy to compare at a glance / bad details still hidden by scaling
  3. Option 2 plus a 1:1 zoom where both halves show the same region pixel for pixel, moved by dragging : good shows real block artifacts in the same spot of both pictures / bad a little more code
- What we chose: Option 3. Keys: s side by side, z 1:1 zoom, drag to move. The original is read from the mp4 in step with the decoded frames, upright and at true size, with its own 30-frame memory for stepping back. (Its code lives in its own file, OriginalVideo.java, since Nov 13 with PM approval.)
- Why (plain words): The difference between n = 2 and n = 5 is easy to miss at reduced size but obvious at 1:1. Showing the same spot in both halves makes the comparison fair.
- Proof: DisplayViewTest (10 tests: fit, black bars, halves, 1:1 crop, pan limits, same region in both halves); OriginalVideoTest (forward, jump ahead, ring, reopen, end of clip, wrong video rejected); real SAL frame 30 rendered through the same code: docs/img/p3_SAL_n2_5_side_1to1.png and p3_SAL_n5_2_side_1to1.png.
- Cost or limitation: The original is read on the player thread (about 6 ms per 1080p frame), so timing measurements are made without it. Stepping back more than 30 frames rereads the mp4 from the start.
- Say it in the presentation: "Press s to put our output next to the original, then z to see both at 1:1: the same spot in each, so you can see exactly where we spent bits."
- Likely professor question: Why does the 1:1 zoom matter? / Answer: Scaling 1080p down to a laptop screen averages pixels together and hides 8x8 block artifacts. At 1:1 every pixel is shown as it is, so the quality gap between layers is visible.

### P3-L12: Warming up the painting code before playback starts
- Date / window: Nov 13, Window C
- Brief says: Play at the original frame rate (D4).
- The problem in plain words: In the fair Mac test, most late frames came in the first 2 seconds. Java turns frequently used code into fast machine code only after running it a while (JIT compilation), so the first paints are slow.
- Options we considered:
  1. Accept it and report two numbers (steady state and whole clip) : good honest, no code / bad the whole-clip number stays at 0.5 to 5.5% late
  2. Repaint frame 0 several times during the pre-roll wait : good the viewer sees no difference (same picture), the painting code is compiled before the clock starts / bad about 150 ms longer start
  3. Tune Java's compiler or memory settings : good could help more / bad harder to explain, outside this step
- What we chose: Option 2, 15 repaints of frame 0 inside the existing pre-roll. Option 3 is parked for the PM.
- Why (plain words): It costs a sixth of a second before the video starts, and it targets the exact code that was slow at the start.
- Proof: PlayerTest prerollWarmsThePaintPathBeforeTheClockStarts (frame 0 painted 1 + 15 times, 0 drops, exact duration). The Mac measurement of whole-clip late frames is queued.
- Cost or limitation: The decoder threads warm up separately during pre-roll; if early late frames remain, memory settings (option 3) are the next lever.
- Say it in the presentation: "Java speeds code up after it has run a while, so we paint the first frame a few extra times while the buffer fills; the viewer sees nothing, but playback starts warm."
- Likely professor question: Isn't that cheating the measurement? / Answer: No. The clock starts after the warm-up and every frame after that is counted. It only moves one-time start-up work to before playback, like a player buffering.

### P3-L13: Showing which blocks are foreground (overlay)
- Date / window: Nov 13, Window C
- Brief says: Foreground visibly cleaner than background when n1 < n2 (D5); visual demarcation of foreground and background (B11, encoder side). The decoder needs nothing except the .cmp (A4).
- The problem in plain words: When the grader sees a sharp region and a blurry one, they should be able to check that the sharp one really is what the encoder called foreground.
- Options we considered:
  1. Run segmentation again inside the decoder : good could show region colors / bad breaks "decoder needs only the .cmp", slow, and could disagree with the encoder
  2. Use the block_type byte already stored for every 8x8 block in the file : good exactly what the encoder decided, no extra data, no segmentation code in the decoder / bad only foreground or background, not which region
- What we chose: Option 2. Press d to tint foreground 8x8 blocks red (3 parts red to 5 parts picture, so detail stays visible). Works in fit, side by side and 1:1 views.
- Why (plain words): The overlay shows the decision that actually chose the quantizer for each block, so sharpness and labels can be compared directly.
- Proof: DisplayViewTest overlayTintsOnlyForegroundBlocks, overlayFindsTheRightBlockRowOnPaddedFrames; images docs/img/p3_video2_n2_5_overlay.png (plain left, overlay right) and docs/img/p3_SAL_n2_5_overlay_1to1.png. With the test files the tint is a checkerboard, matching their fake labels; real labels come after wire-up.
- Cost or limitation: No per-region colors in the decoder; the encoder's VizSeg tool shows those (B11).
- Say it in the presentation: "Press d and the decoder tints every block the encoder marked as foreground, straight from the file, so you can see that the sharp blocks are exactly the foreground ones."
- Likely professor question: How does the overlay know what is foreground without segmentation code? / Answer: Every 8x8 block in our file starts with a block_type byte, 1 for foreground. The decoder reads that byte anyway to pick n1 or n2, and the overlay simply colors those blocks.

### P3-L14: Clear errors and exit codes for mydecoder
- Date / window: Nov 13, Window C
- Brief says: `mydecoder input_video.cmp` decodes and renders (A3); the decoder needs nothing except the .cmp (A4). Error handling is not mentioned.
- The problem in plain words: During a demo or grading, someone may pass the wrong file (the mp4 instead of the .cmp), a file from a crashed encode, or a different video as the original. A stack trace or a garbled picture would look broken.
- Options we considered:
  1. Let Java exceptions surface : good no code / bad long stack traces that hide the real reason
  2. One plain error line per problem, plus a fixed exit code (2 for a bad command line, 1 for a bad file) : good the reason is obvious and scripts can check the result / bad every case needs a test
- What we chose: Option 2. Every check happens before a window opens, so a bad file never shows a half-drawn picture.
- Why (plain words): The file format already detects every kind of damage (P2-L6, P2-L8); this step makes sure the person running the decoder sees that reason in one line.
- Proof: DecoderMainErrorsTest (10 tests): no arguments, missing file, non-number after --workers, an mp4 passed as the .cmp, file shorter than the header, wrong version, unfinished file, truncated file, bench on a bad and a good file, original that is a different video.
- Cost or limitation: None worth noting.
- Say it in the presentation: "Give the decoder the wrong file and it tells you why in one line, for example 'unfinished .cmp file: the encoder did not close it', before any window opens."
- Likely professor question: What happens if the encoder crashed halfway? / Answer: The file's footer position is still zero, so the decoder prints "unfinished .cmp file" and exits with code 1. It never plays a partial file.
