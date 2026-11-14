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

### P1-L6: Layered (hierarchical) search as the default
- Date / window: Nov 12, Window A (Project decision D2)
- Brief says: a fast motion search is "even better" than brute force (B4).
- The problem in plain words: full search is accurate but too slow (1.24 s per 1080p frame on the Mac at +/-16, four times that at +/-32).
- Options we considered:
  1. Three-step or diamond search (jump in big steps toward lower error) : good, very fast / bad, often gets stuck in the wrong valley on textured scenes.
  2. Layered search: shrink the frame to half and quarter size, search widely on the tiny image, then refine at each bigger size : good, covers +/-32 px cheaply / bad, fine detail blurs in the tiny image.
  3. Full search everywhere : good, simplest / bad, about 9 minutes for SAL alone.
- What we chose: 3 layers (quarter, half, full), +/-8 at quarter size (about +/-32 at full), +/-2 refine per layer, plus a few extra guesses at full size (no motion, the neighbours' vectors, last frame's vector, the camera motion) and a final +/-1 polish.
- Why (plain words): a 32 pixel move is only 8 pixels at quarter size, so the wide search is cheap there; each bigger layer only corrects the answer by a pixel or two.
- Proof: test `HierarchicalSearchTest.recovers30PixelShiftThatFullSearch16Cannot`; RESULTS "P1.3": 3 to 6 times faster than full search, agreement 89.8% to 95.8%.
- Cost or limitation: SAL agreement is 89.8%, 0.2 points under the 90% target, mostly on the brick wall where several positions match equally well.
- Say it in the presentation: "I search a quarter-size image first, where a 32 pixel move is only 8 pixels, then refine on the bigger images."
- Likely professor question: How do you know the fast search is right? / Answer: I compare it with full search on 5 frame pairs per clip. It agrees within 1 pixel on 90 to 96% of textured blocks, and only 1.6 to 4% of blocks get a clearly worse match.

### P1-L7: Refine the 3 best coarse guesses, not just 1
- Date / window: Nov 12, Window A (P1-D6, P1-D7)
- Brief says: brief is silent.
- The problem in plain words: on the quarter-size image, textures blur, so the single best coarse match is sometimes the wrong one.
- Options we considered:
  1. Refine only the best coarse match : good, fastest / bad, SAL agreement 87.3%.
  2. Add a +/-1 polish at full size : good, cheap / bad, only fixes near misses (89.0%).
  3. Also keep the 3 best coarse matches and refine each : good, recovers wrong-valley picks (89.8%) / bad, about 1.8 times slower.
- What we chose: options 2 and 3 together.
- Why (plain words): each step measurably reduced real misses, and the search is still about 2 times faster than full search at 1080p and 6 times faster on video2.
- Proof: RESULTS "P1.3" (87.3% to 89.0% to 89.8% on SAL; video2 95.8%).
- Cost or limitation: sandbox time per 1080p frame rose from 109 to 193 ms; the Mac number is pending (target 200 ms).
- Say it in the presentation: "Instead of trusting one blurry coarse answer, I refine the three best and keep whichever matches best at full size."
- Likely professor question: Why exactly 3? / Answer: It was the smallest number that fixed most wrong-valley picks; more starts cost time without measurable gain in agreement.

### P1-L8: A small penalty for vectors that disagree with neighbours (lambda)
- Date / window: Nov 12, Window A (Project decision D2)
- Brief says: foreground regions should be consistent in motion direction (B7); silent on how vectors are found.
- The problem in plain words: on bricks, railings and stripes the pattern repeats, so a position one brick away can match almost as well as the true one.
- Options we considered:
  1. Pure lowest match error : good, textbook / bad, picks period-shifted vectors on repeating textures.
  2. Lowest match error plus a small cost for being far from the neighbours' typical vector (the median of left, top and top-right) : good, breaks near-ties toward the smooth answer / bad, a tiny object moving against its neighbours needs a clearly better match to win.
- What we chose: option 2 with weight 4 per pixel of distance (a 256-pixel block's match error is usually in the hundreds to thousands, so this only decides near-ties).
- Why (plain words): the penalty only matters when two positions are almost tied, which is exactly the repeating-texture case.
- Proof: RESULTS "P1.3" (agreement with lambda 4 is equal or higher than lambda 0 on every clip). Further tuning in P1.9.
- Cost or limitation: the effect on agreement is small (under 1 point); its value shows later in cleaner camera-motion fits and fewer false foreground blocks.
- Say it in the presentation: "When two positions match almost equally, I prefer the one that agrees with the neighbours, which stops bricks from producing fake motion."
- Likely professor question: Doesn't that blur real object edges? / Answer: Only when the match is nearly tied. A block on a real moving object matches its true position much better, so it still wins.

### P1-L9: Only trust vectors from textured, well-matched blocks
- Date / window: Nov 12, Window A (P1-D10; Project decision D3 step 1)
- Brief says: classify from motion only (B9); every block needs a label (B10).
- The problem in plain words: a vector from a flat block (sky, dark road) or from a block that found no good match is noise, and noise looks like motion.
- Options we considered:
  1. Use every vector : good, simple / bad, flat areas and occlusions become fake foreground.
  2. Mark a block reliable only if it has enough texture (brightness variance at least 100, a standard deviation of 10 levels) and its best match is close (average difference at most 12 levels per pixel) : good, removes most noise / bad, video1 keeps only 31% of blocks.
  3. Texture test only : good, simpler / bad, keeps blocks that were covered or blurred between frames.
- What we chose: option 2. Unreliable blocks do not vote; they take their neighbours' label later (P1.7).
- Why (plain words): the camera-motion fit and the grouping step both work better on fewer, trustworthy vectors than on many noisy ones.
- Proof: tests in `SegmenterReliabilityTest`; RESULTS "P1.4" (31.4% to 68.5% reliable).
- Cost or limitation: in the dark video1 most blocks are unreliable, so its labels lean more on neighbours and on the 2-of-3 frame vote.
- Say it in the presentation: "Blocks that are too flat or that found no good match do not get a vote; they borrow their label from neighbours."
- Likely professor question: Why variance 100 and MAD 12? / Answer: They are starting values that remove sky, road and padding while keeping textured surfaces. All thresholds are tuned once, on all four clips together, in the tuning step.

### P1-L10: Accepting 89.8% search agreement on SAL
- Date / window: Nov 13, Window A (PM ruling on the P1.3 parcel)
- Brief says: block matching by MAD, brute force or a faster search (B4); silent on accuracy targets.
- The problem in plain words: our own target was 90% agreement between the fast search and the slow exact search; SAL reached 89.8%.
- Options we considered:
  1. Spend more search time until SAL passes : good, the number looks clean / bad, slower, and the extra matches it would find are often wrong anyway.
  2. Accept it and report the real-miss rate beside it : good, honest about what the gap is / bad, one number is below its target.
- What we chose: option 2. We always show agreement (89.8%) together with "real misses" (fast search clearly worse, 4.0%).
- Why (plain words): on a brick wall the pattern repeats, so a vector one brick away can match equally well. Those disagreements are not mistakes by the fast search; even the exact search picks wrong "brick alias" vectors when given a wider window.
- Proof: RESULTS "P1.3" (SAL 89.8%, worse by >10% only 4.0%; with a +/-32 exact search the exact search itself finds lower-error aliases).
- Cost or limitation: about 4% of SAL blocks get a genuinely worse vector; reliability, the camera fit and the neighbour rule absorb them.
- Say it in the presentation: "Ninety percent agreement is the headline, but the honest number is real misses, which stay at 4% or below on every clip."
- Likely professor question: Why not just use full search? / Answer: It takes about 400 ms per 1080p frame in the sandbox and over a second at the range video2 needs. Hierarchical is 2 to 6 times faster and its disagreements are mostly equally good matches.

### P1-L11: Finding the camera's motion with RANSAC and a homography
- Date / window: Nov 13, Window A (P1-D11 to P1-D13; Project decision D3 step 2)
- Brief says: background has near-zero vectors for a still camera, or one constant vector for a moving camera (B6).
- The problem in plain words: all four clips have a moving camera, and on Stairs the camera tilts and the scene has depth, so background vectors are not one constant; we need a model of "what the camera alone would do" at every block.
- Options we considered:
  1. One constant vector (the brief's literal suggestion; the median) : good, simplest / bad, explains only 56 to 76% of Stairs blocks, so the rest would look like foreground.
  2. Affine model (6 numbers: shift, rotation, zoom, shear) : good, handles rotation and zoom / bad, cannot represent perspective.
  3. Homography (8 numbers: exact for a flat surface or a rotating camera), fitted with RANSAC, falling back to affine and then a constant vector : good, explains 77 to 90% of trustworthy blocks / bad, more code (a hand-written 8x8 solver).
- What we chose: option 3. RANSAC repeatedly fits the model to 4 random blocks and keeps the fit most blocks agree with (within 2.5 px), then refits on all those blocks. Moving people are outliers and do not spoil the fit. Fixed random seed, so the result is the same every run.
- Why (plain words): if the camera model is wrong, every background block has leftover motion and becomes false foreground. The homography is the simplest model that matches how a real camera sees a mostly flat wall or staircase.
- Proof: `GlobalMotionTest` (a known homography with 30% junk vectors is recovered within 0.5 px); RESULTS "P1.5" (homography accepted on 1679 of 1680 frame pairs, mean inliers 0.74 to 0.90).
- Cost or limitation: near-camera parallax (video2's ground and trees) breaks the "flat scene" assumption, so those blocks stay outliers; that is the main source of false foreground left for P1.6 to P1.8.
- Say it in the presentation: "I fit the camera's own motion with a homography using RANSAC, so the walker's blocks are outvoted and the background, wherever the camera points, ends up with zero leftover motion."
- Likely professor question: Why does a homography fit Stairs better than a translation? / Answer: The camera tilts while following the man down the stairs, so near and far parts of the scene shift by different amounts and the image changes in perspective. A single shift cannot describe that; a homography can.

### P1-L12: The naive baseline we must beat
- Date / window: Nov 13, Window A (P1-D14, P0-D5)
- Brief says: foreground regions must be contiguous and consistent in motion direction (B7).
- The problem in plain words: to show that each later step helps, we need a fixed "before" picture made with the same vectors and camera model.
- Options we considered:
  1. Reuse the PM prototype image : good, already exists / bad, made by different code, so comparisons are not fair.
  2. Same code, simplest rule: textured block with leftover motion over 3 px, keep groups of 4 or more blocks : good, isolates what the later steps add / bad, it already benefits from our camera model.
- What we chose: option 2, saved at 20/50/80% of each clip before any tuning.
- Why (plain words): an honest before/after needs the same inputs on both sides.
- Proof: `docs/img/naive_*.jpg`, RESULTS "P1.5 naive baseline overlays".
- Cost or limitation: the baseline is a bit stronger than the prototype, which makes our later improvements look smaller, not bigger.
- Say it in the presentation: "This is what you get if you just threshold leftover motion: the walker is found, but brick walls and fences light up too. Everything after this step exists to remove those."
- Likely professor question: Why does the brick wall show up as moving? / Answer: Bricks repeat every few pixels, so some blocks pick a vector one brick away. That leftover is big but random in direction, which the direction-consistency rule in the next step removes.

### P1-L13: Grouping blocks whose leftover motion agrees (and a looser gate for who may be foreground)
- Date / window: Nov 13 to 14, Window A (P1-D15, P1-D16; Project decision D3 steps 3 to 5)
- Brief says: foreground regions are contiguous and consistent in motion direction ("important!", B7); several moving regions are allowed (B8).
- The problem in plain words: after removing the camera's motion, brick-wall and fence blocks still have leftover motion, but it points in random directions. A walker's blocks all move the same way.
- Options we considered:
  1. Keep any connected group of moving blocks (the naive baseline) : good, simple / bad, 11 to 25% of some frames lit up falsely.
  2. Grow regions only through neighbours whose leftover motion agrees in size or direction : good, exactly the brief's rule / bad, needs thresholds.
  3. Same rule, but only on blocks trusted for the camera fit (texture >= 100) : good, least noise / bad, removed the walkers too (Stairs walker: 0 blocks).
- What we chose: option 2, with a looser entry gate (good match and texture >= 20) than the camera fit uses.
- Why (plain words): a person in a dark jacket has few textured blocks, but our search already gives a flat block its neighbours' vector, so its leftover motion still tells the truth. The agreement rule removes the extra noise.
- Proof: RESULTS "P1.6" (naive vs steps A and B; Stairs f173 walker 0 -> 222 blocks); `SegmenterGroupTest` (two touching people moving opposite ways stay separate; random-direction patch mostly dropped).
- Cost or limitation: a few aligned noise blocks can still chain into a small region; the 2-of-3 frame vote (P1.8) removes most of those.
- Say it in the presentation: "Neighbouring blocks only join the same object if they move the same way, which is the brief's contiguity and consistency rule written as code."
- Likely professor question: Why not just raise the motion threshold? / Answer: Brick aliases have large leftover motion, often larger than the walker's, so a threshold cannot separate them. Their direction is random, and that is what gives them away.

### P1-L14: Measuring how scattered a region's motion is, robustly
- Date / window: Nov 14, Window A (P1-D17)
- Brief says: silent.
- The problem in plain words: we drop regions whose blocks disagree too much. A walker's legs move about twice as fast as his torso, and a couple of limb blocks have wild vectors.
- Options we considered:
  1. Average spread (root mean square), cap 3 px : good, standard / bad, three wild blocks out of 200 rejected the whole walker on Stairs.
  2. Median spread, cap 2 px : good, ignores a few wild blocks / bad, still rejected the SAL walker (torso 9 px, legs 20 px).
  3. Median spread, cap 3 px : good, keeps both walkers / bad, a noisy patch whose majority agrees can pass.
- What we chose: option 3.
- Why (plain words): a median is not moved by a handful of bad blocks, and 3 px allows a person's torso and legs to move at different speeds while still rejecting patches that point every which way.
- Proof: RESULTS "P1.6" (Stairs f173 walker 0 -> 222 blocks; SAL f218 walker 0 at 2 px, 88 at 3 px).
- Cost or limitation: video2 foreground rose (8 to 12% at its peak frames) because more tree and fence regions also pass; the camera test and the frame vote target those.
- Say it in the presentation: "I use a median, not an average, so two bad blocks on an ankle cannot throw away the whole person."
- Likely professor question: Why 3 px? / Answer: It was the smallest cap that kept the walkers on both 1080p clips. All thresholds get a final joint tuning pass on all four clips.

### P1-L15: Asking "could the camera explain this block?"
- Date / window: Nov 14, Window A (P1-D18)
- Brief says: classification from motion only (B9).
- The problem in plain words: on a repeating brick wall the search can lock a whole patch onto the same wrong vector, one brick over. Those wrong vectors agree with each other, so the agreement rule cannot catch them.
- Options we considered:
  1. Accept them as foreground : good, no extra work / bad, visible false patches on SAL.
  2. Bigger smoothness penalty in the search : good, fixes some / bad, also blurs real object edges everywhere.
  3. For each candidate, measure how well the camera's own vector matches it; if almost as well as its best vector, call it background : good, uses only motion and match error / bad, 9 extra block comparisons per block.
- What we chose: option 3 (slack: 1 grey level per pixel, or 15% of the block's best error).
- Why (plain words): on a repeating texture several vectors match equally well, including the camera's, so the block has no evidence of its own motion. A walker matches badly at the camera's vector, so he stays.
- Proof: RESULTS "P1.6" step D (SAL f218 5.6% -> 3.7%, video1 f259 6.2% -> 3.5%); `SegmenterCameraTest` (a striped patch with consistent aliases is fully removed; a block moving 10 px against the camera is kept).
- Cost or limitation: an object moving slowly over a repeating texture could be called background; small brick patches still survive on SAL.
- Say it in the presentation: "Before I call a block foreground, I check whether the background's motion would explain it just as well. If it would, there is no evidence it moved on its own."
- Likely professor question: Is that still classification from motion only? / Answer: Yes. It compares two motion hypotheses for the same block using the match error, the same quantity the motion search itself uses. No colour, shape or content rules.

### P1-L16: Accepting that a slow, distant walker can be missed
- Date / window: Nov 14, Window A (P1-D19; PM time box 2 h)
- Brief says: every macroblock of every frame gets a label (B10); foreground is what moves differently from the background (B6, B7).
- The problem in plain words: at the top of the Stairs clip the walker is small and far, and moves only about 2 px per frame relative to the camera. Our camera model itself is accurate to about 0.7 px, and block matching measures in whole pixels.
- Options we considered:
  1. Lower the motion threshold to 1.5 px : good, simple / bad, still found none of him, and video2 false foreground rose by up to 3 points.
  2. Also make the camera test exact (no 1 px slack) : good, recovered about a quarter of him / bad, video2 false foreground nearly doubled (6.5% to 12.1% on one frame).
  3. Threshold scaled to each frame's camera-fit error : good, principled / bad, still missed him.
  4. Accept the miss and document it : good, keeps the other three clips clean / bad, the first seconds of Stairs show him as background.
- What we chose: option 4.
- Why (plain words): 2 px of motion is barely above our measurement noise, so any per-frame rule that catches him also catches noise everywhere else. False foreground costs bits on every clip; this miss costs quality on a few seconds of one.
- Proof: RESULTS "P1.6 known limit" (table of all four experiments on 12 frames).
- Cost or limitation: the distant walker in early Stairs frames is compressed with the background quantizer.
- Say it in the presentation: "When the walker is far away he moves only two pixels a frame against the camera, which is at the edge of what block matching can measure, so I accepted that miss rather than flood the other clips with false foreground."
- Likely professor question: How would you fix it with more time? / Answer: Add up each block's leftover motion over three or four frames. A 2 px per frame mover becomes 6 to 8 px, well above the noise, while random noise does not add up.

### P1-L17: Filling holes inside a moving person, but not growing regions
- Date / window: Nov 14, Window A (P1-D20; Project decision D3 step 5)
- Brief says: every macroblock gets a label (B10); foreground regions are contiguous (B7).
- The problem in plain words: a walker's region has holes where his clothing is flat or a block failed a test. Those holes would be compressed with the coarse background quantizer, which looks like blotches on a person.
- Options we considered:
  1. Leave labels as grouped : good, no assumptions / bad, speckled people.
  2. Fill: a block surrounded on 3 of 4 sides by one region joins it, and pockets fully enclosed by one region are filled : good, closes holes without new evidence / bad, a real gap inside an object (between arm and body) could be filled.
  3. Also grow every region by one block : good, covers object edges / bad, foreground doubled on every clip because false regions grow too.
- What we chose: option 2; option 3 is built but switched off.
- Why (plain words): filling only changes blocks that are already surrounded by one object, so it cannot create new false regions. Growing does, and false foreground costs bits everywhere.
- Proof: RESULTS "P1.7 fill" (fill +0 to 0.6 points; dilation e.g. video2 12.7% to 25.2%); `SegmenterFillTest` (6 tests: enclosed hole, notch, two-region pocket, border pocket, oversized pocket, dilation).
- Cost or limitation: flat dark trousers that are not enclosed (they reach the stairs below) stay background.
- Say it in the presentation: "If a block is surrounded by the walker, it is part of the walker. That rule fills the holes but never invents a new object."
- Likely professor question: Why not dilate to be safe, since false foreground only costs bits? / Answer: We measured it: dilation doubled foreground on every clip, mostly around false regions. That doubles the bits spent on the wrong blocks for a small gain at object edges.

### P1-L18: A 2-of-3 frame vote that follows the motion
- Date / window: Nov 14, Window A (P1-D21, P1-D22; Project decision D3 step 6)
- Brief says: every macroblock of every frame gets a label (B10); silent on temporal smoothing.
- The problem in plain words: a block can be called foreground in one frame and background in the next for no real reason (noise, a bad match). On playback that flicker is visible as a patch switching between sharp and blurry.
- Options we considered:
  1. No smoothing : good, simplest / bad, flicker.
  2. Vote at the same block position in neighbouring frames : good, simple / bad, the camera moves up to two blocks per frame on video2, so it would compare a person with the empty spot he just left.
  3. Vote 2 of 3 frames, looking up each neighbour frame where the block actually was, using the motion vectors : good, follows moving people / bad, needs one frame of delay.
- What we chose: option 3, streamed with one frame of lookahead (the encoder never holds the clip).
- Why (plain words): a real moving person is foreground in consecutive frames, a noise blip is not. Following the vectors makes the vote compare the same piece of the scene.
- Proof: `SegmenterTemporalTest` (blip removed, one-frame gap filled, mover kept with no ghost); RESULTS "P1.8" (16 to 22% of foreground removed on video1, video2, SAL; whole SAL in 102 s in the sandbox).
- Cost or limitation: the first and last frame are not smoothed; region colours in the overlay can change between frames because ids are not matched (the file stores only foreground or background, so this does not affect compression).
- Say it in the presentation: "A block has to be foreground in two of three consecutive frames, and I follow its motion vector to find it in the neighbouring frames, so a person who moves a whole block per frame is still counted."
- Likely professor question: Why 2 of 3 and not more frames? / Answer: Each extra frame adds a frame of delay and more chance of losing fast, short motions like a swinging leg. Two of three removes single-frame flicker, which was the problem we saw.
