# Decision Log: P1 Segmentation (Window A)

Plain-language record of every P1 choice that could reasonably have gone another way. Oldest first.
Technical source: repo `docs/DECISIONS.md` (P1-D*) and Project doc 03 (D2, D3, D4, D12).

### P1-L1: Full search as the trusted reference, SAD as the cost
- Date / window: Nov 10, Window A
- Brief says: block matching with MAD, brute force or a faster search; a fast method is "even better" (B4).
- The problem in plain words: we need motion vectors we can trust before building anything faster, and we need a cost that says how well two 16x16 blocks match.
- Options we considered:
  1. Only a fast search : good, quick / bad, no way to prove its answers are right.
  2. Full search (try every offset in the window) kept as a reference, fast search checked against it : good, provable / bad, the reference is slow.
  3. MSE (mean squared error) as the cost : good, punishes big errors / bad, needs multiplications and is pulled around by a few bad pixels.
- What we chose: full search as the reference, with SAD (sum of absolute differences over the 256 pixels).
- Why (plain words): SAD is the brief's MAD multiplied by 256, so both always pick the same vector, and SAD stays a whole number, so results are identical on every machine.
- Proof: `MotionEstimatorTest` (known shifts recovered exactly, oracle comparison); RESULTS "P1.1".
- Cost or limitation: full search over +/-16 pixels took 382 ms per SAL frame in the sandbox, too slow as the default.
- Say it in the presentation: "I first built the slow search that tries every position, and used it as the answer key for the fast one."
- Likely professor question: Why SAD and not MAD? / Answer: MAD is SAD divided by 256, so both choose the same block. SAD avoids the division and stays exact.

### P1-L2: Tie-break toward the smallest motion
- Date / window: Nov 10, Window A (P1-D1)
- Brief says: brief is silent on ties.
- The problem in plain words: on flat areas (sky, road) many offsets match equally well, so the "best" vector would be random.
- Options we considered:
  1. Take the first equal match found : good, simple / bad, arbitrary vectors that look like motion.
  2. Prefer the smallest |dx| + |dy| among equal matches, start from (0, 0) : good, flat blocks report "no motion" / bad, a block that truly moved over a flat area reports (0, 0).
- What we chose: option 2, then scan order as the last tie-break.
- Why (plain words): random vectors on flat areas would look like moving objects. Reporting zero is honest, and the reliability check (P1.4) flags those blocks anyway.
- Proof: `MotionEstimatorTest` flat-frame case returns (0, 0) everywhere.
- Cost or limitation: true motion over textureless areas is invisible to any block matcher (the aperture problem).
- Say it in the presentation: "When several positions match equally, I pick the smallest motion, so flat sky never pretends to move."
- Likely professor question: What about a white wall that really moved? / Answer: No block matcher can see that, because every position looks the same. Those blocks are marked unreliable and take their label from neighbours.

### P1-L3: Keep the search window inside the frame
- Date / window: Nov 10, Window A (P1-D2)
- Brief says: brief is silent.
- The problem in plain words: near the frame edge some offsets would point outside the previous frame.
- Options we considered:
  1. Invent pixels outside (repeat the edge) : good, same window everywhere / bad, matches against pixels that never existed.
  2. Shrink the window at the edges : good, only real pixels / bad, motion leaving the frame can be missed.
- What we chose: shrink the window (clamp).
- Why (plain words): a match against made-up pixels is not evidence of motion.
- Proof: oracle test matches exactly, including edge blocks.
- Cost or limitation: border blocks see a smaller window.
- Say it in the presentation: "At the borders the search only looks at real pixels."
- Likely professor question: Does this hurt at the edges? / Answer: Slightly, for objects entering or leaving. The camera motion fit uses the whole frame, so one border block does not change it.

### P1-L4: Stop adding up a candidate once it cannot win
- Date / window: Nov 10, Window A (P1-D3)
- Brief says: brute force or faster (B4).
- The problem in plain words: full search wastes time finishing sums for candidates that are already worse than the best so far.
- Options we considered:
  1. Always sum all 256 pixels : good, simplest / bad, slow.
  2. Check after each row and stop when the sum is already larger than the best : good, same answer, about 3 times faster / bad, a few more lines.
- What we chose: option 2, stopping only when strictly larger so ties are still decided correctly.
- Why (plain words): it changes nothing in the result and saves about two thirds of the work.
- Proof: oracle test without early exit gives identical vectors.
- Cost or limitation: none.
- Say it in the presentation: "The search drops a candidate as soon as it is already worse than the best one."
- Likely professor question: Can early exit change the answer? / Answer: No. It only stops when the candidate is already strictly worse, so the winner and ties are unchanged.

### P1-L5: Search range +/-32 for video2, +/-16 elsewhere
- Date / window: Nov 10, Window A (from P1.1 measurements; Project decision D2)
- Brief says: brief is silent on range.
- The problem in plain words: video2 is filmed running alongside joggers, so the background moves more than 16 pixels per frame.
- Options we considered:
  1. +/-16 everywhere : good, faster / bad, 29% of video2 blocks hit the window edge (wrong vectors).
  2. +/-32 everywhere with full search : good, catches everything / bad, 4 times slower.
  3. A layered search that covers +/-32 cheaply (P1.3) : good, both / bad, more code.
- What we chose: the reference uses +/-32 on video2 and +/-16 elsewhere; the default fast search covers about +/-32 on every clip.
- Why (plain words): at +/-32 only 2.2% of video2 blocks hit the edge and the match error drops from 5.76 to 4.08 per pixel.
- Proof: RESULTS "P1.1" table.
- Cost or limitation: full search at +/-32 is slow, so it is used only for checking.
- Say it in the presentation: "video2's camera moves up to 16 pixels a frame, so the search must reach 32."
- Likely professor question: How did you choose the range? / Answer: By measuring. At +/-16, 29% of video2 blocks sat on the window edge, a sign the true motion was outside it.
