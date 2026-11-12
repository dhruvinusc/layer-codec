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
