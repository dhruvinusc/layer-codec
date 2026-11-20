# P2 Compression and .cmp file: decision log

Owner: Window C (backfilled Nov 12 from repo docs/DECISIONS.md P2-D1 to P2-D10 and Project doc 03 D6, D7; Window B built P2 and is closed). Oldest first.

### P2-L1: The exact DCT formula
- Date / window: Nov 11, Window B (backfilled by Window C)
- Brief says: 8x8 DCT on R, G and B (C2). The formula itself is not given.
- The problem in plain words: There are several DCT variants with different scale factors. Encoder and decoder must use the same one, and it should be the one the class teaches.
- Options we considered:
  1. Class/JPEG formula, orthonormal (energy is preserved, the inverse is the same formula transposed) : good matches lectures, exact round trip / bad needs the 1/sqrt(2) factor for the first coefficient
  2. Unscaled DCT : good slightly simpler / bad coefficient sizes depend on the scale, harder to reason about quantization
  3. Fast integer DCT (like modern codecs) : good faster / bad approximate, harder to defend, not what was taught
- What we chose: The class formula F(u,v) = 1/4 C(u) C(v) sum sum f(x,y) cos(...) cos(...), C(0) = 1/sqrt(2), with a level shift (subtract 128 from every pixel first, add it back after the inverse).
- Why (plain words): It is the formula the professor teaches, so every number can be checked by hand. The level shift centers pixels around zero, which keeps the first coefficient small enough to store in 16 bits.
- Proof: DctTest: matches a slow textbook double sum on 200 random blocks within 1e-9; a flat block of 200 gives DC 576 and all other coefficients 0.
- Cost or limitation: Uses doubles and a precomputed cosine table, so it is slower than an integer DCT. Still fast enough (decode measured in P3.1).
- Say it in the presentation: "We used the exact DCT formula from class, and we test it against a slow textbook version to 9 decimal places."
- Likely professor question: What is the DC of a flat block of 200? / Answer: Subtract 128 to get 72, and the DC is 8 times that, so 576. Every other coefficient is 0 because a flat block has no variation.

### P2-L2: Coefficient order inside a block
- Date / window: Nov 11, Window B (backfilled by Window C)
- Brief says: Blocks hold 64 coefficients per channel; zigzag is not expected for the baseline (C6, F2 is extra credit).
- The problem in plain words: The 64 numbers of a block can be written in different orders. The order changes nothing about quality, only how easy it is to compress later.
- Options we considered:
  1. Natural row-major order (row by row, left to right) : good simplest, exactly what the brief shows / bad zeros are scattered, so run-length coding would not help
  2. Zigzag (low to high frequency) : good groups zeros at the end / bad belongs to extra credit, adds a mapping table
- What we chose: Natural row-major order in the baseline. Zigzag is kept for extra credit (P4).
- Why (plain words): The baseline should match the brief exactly. Zigzag only pays off together with run-length coding, which is extra credit.
- Proof: CmpDump prints blocks in the brief's layout; CmpFormatTest blocksAreInScanLineOrder385BytesEach.
- Cost or limitation: zlib compresses somewhat less than it would with zigzag.
- Say it in the presentation: "The baseline writes coefficients in plain row order, exactly as the brief shows; zigzag is our extra credit."
- Likely professor question: Why does zigzag help? / Answer: After quantization most high-frequency coefficients are zero, and zigzag visits them last. That turns them into one long run of zeros that run-length coding stores in a few bytes.

### P2-L3: Keeping every lossy step in one place
- Date / window: Nov 11, Window B (backfilled by Window C)
- Brief says: Brief is silent on where rounding happens.
- The problem in plain words: Rounding can happen in the DCT, in quantization, or when turning numbers back into pixels. If it is spread around, nobody can say where quality was lost.
- Options we considered:
  1. Round inside the DCT : good integers everywhere / bad the DCT itself becomes lossy and harder to test
  2. DCT stays exact; quantization and the final pixel step do all the rounding : good one clear place for loss / bad needs doubles in between
- What we chose: The DCT and inverse DCT return exact (unrounded) numbers. Loss happens only in quantization, plus rounding and clamping (cutting values to the 0 to 255 range) when making pixels.
- Why (plain words): It makes the claim "the DCT loses nothing, quantization is the only lossy step" literally true in the code, and testable.
- Proof: DctTest forwardThenInverseReturnsInput (error under 1e-9); at n = 0 the whole codec reaches 59 dB (only final rounding lost), RESULTS P2.9.
- Cost or limitation: None worth noting.
- Say it in the presentation: "Information is lost in exactly one place: dividing by 2^n and rounding."
- Likely professor question: Where exactly is information lost? / Answer: In quantization, when a coefficient is divided by 2^n and rounded to a whole number. The DCT and its inverse are exact, and at n = 0 we still get 59 dB because only pixel rounding remains.

### P2-L4: How quantization rounds
- Date / window: Nov 11, Window B (backfilled by Window C)
- Brief says: Uniform quantization, every table entry 2^n, n1 for foreground and n2 for background (C3).
- The problem in plain words: Dividing by 2^n gives fractions. We must turn them into whole numbers, and negative numbers must be treated fairly.
- Options we considered:
  1. Truncate (drop the fraction) : good simplest / bad about twice the average error, and pulls every value toward zero
  2. Round half away from zero : good half the error of truncation, negatives behave like positives / bad one extra line of code
  3. Different steps per coefficient (like JPEG tables) : good better quality per bit / bad the brief asks for 2^n everywhere
- What we chose: q = round(c / 2^n), halves rounded away from zero, same step for all 64 coefficients, n allowed from 0 to 10. Decoder multiplies back: c' = q x 2^n.
- Why (plain words): It follows the brief exactly and gives the smallest error for that rule. Symmetric rounding means -12 and 12 are treated the same way.
- Proof: QuantTest roundingHasAboutHalfTheErrorOfTruncation (ratio 0.45 to 0.55); worked examples at n = 3: 13 to 2 to 16, -12 to -2 to -16, 3 to 0.
- Cost or limitation: A flat 2^n step treats high and low frequencies the same, which wastes bits compared to JPEG tables.
- Say it in the presentation: "Each coefficient is divided by 2 to the n and rounded; bigger n means more coefficients become zero."
- Likely professor question: Why round and not truncate? / Answer: Rounding keeps the error within half a step, truncation allows up to a whole step. We measured rounding at about half the average error.

### P2-L5: 16-bit storage for coefficients
- Date / window: Nov 11, Window B (backfilled by Window C)
- Brief says: Brief is silent on number size.
- The problem in plain words: Each coefficient needs a fixed number of bytes in the file. Too few bytes overflow; too many waste space.
- Options we considered:
  1. 8 bits : good small / bad the DC can reach 1024, which does not fit
  2. 16 bits (short, range -32768 to 32767) : good always fits after the level shift / bad 2 bytes per coefficient
  3. 32 bits : good never a worry / bad doubles the file for nothing
- What we chose: 16-bit signed integers, with a check that throws an error if anything ever overflows.
- Why (plain words): After subtracting 128, the largest possible coefficient is 1024 at n = 0, so 16 bits is always enough, and zlib removes most of the empty space anyway.
- Proof: DctTest extremeBlocksStayInsideInt16 (black block DC -1024, white 1016); QuantTest extremeDcFitsAtN0AndOverflowIsCaught.
- Cost or limitation: Raw size is 12.57 MB per 1080p frame before zlib.
- Say it in the presentation: "Thanks to the level shift every coefficient fits in 16 bits, and we prove it with an all-black and all-white block."
- Likely professor question: What breaks without the level shift? / Answer: A white block would have DC 8 x 255 = 2040 and the worst case grows, so we would need more bits or risk overflow. Centering pixels at zero keeps DC between -1024 and 1016.

### P2-L6: A binary file with a header, instead of the brief's text layout
- Date / window: Nov 11, PM + Window B (03 D7, frozen as version 1; backfilled by Window C)
- Brief says: The file holds n1, n2, then per block: block_type and 64 coefficients for each of R, G, B (C5). A text-like layout is suggested.
- The problem in plain words: The suggested layout has no width, height, frame rate or frame count, so the decoder could not play the file using only the .cmp (A4). As text, SAL would be over 15 GB.
- Options we considered:
  1. Text exactly as suggested : good literal match / bad decoder cannot know size or fps; huge and slow to parse
  2. Binary with the same content in the same order, plus a 48-byte header : good self-contained, compact, fast / bad not human-readable without a tool
- What we chose: Binary, big-endian (most significant byte first), 48-byte header (magic "LYRC", version, flags, true and padded size, fps, frame count, n1, n2, footer position), then per frame the blocks in scan line order: 1 type byte plus 192 coefficients (385 bytes per block).
- Why (plain words): The logical content and order are exactly the brief's, we only add what the decoder needs to stand alone. CmpDump prints any frame in the brief's text layout to show the mapping.
- Proof: CmpFormatTest (17 tests, one per validation rule); CmpDump output matches the brief's layout.
- Cost or limitation: You need CmpDump to read the file by eye.
- Say it in the presentation: "Same content and order as the brief, stored in binary with a small header so the decoder needs nothing but the .cmp."
- Likely professor question: Why not follow the suggested format exactly? / Answer: It has no size, frame rate or frame count, so the decoder could not play it alone. Our dump tool prints our file in the suggested layout, so nothing is hidden.

### P2-L7: zlib on each frame
- Date / window: Nov 11, Window B (backfilled by Window C)
- Brief says: Entropy coding is not expected for the baseline (C6).
- The problem in plain words: Raw coefficients are 12.57 MB per 1080p frame, 5.5 GB for SAL. Files that big are slow to write and read and may not fit on disk.
- Options we considered:
  1. No compression : good simplest / bad 5.5 GB per clip, disk and time limits
  2. zlib (a standard lossless packer from the Java library) per frame : good 3x to 9.6x smaller, exact, frames stay independent / bad about 38 ms to pack and 14 ms to unpack a 1080p frame
  3. Hand-written Huffman or run-length coding : good graded as extra / bad time, belongs with zigzag in P4
- What we chose: zlib level 1 (fastest setting) on each frame separately, marked by a flag bit so it can be turned off.
- Why (plain words): zlib is lossless (it changes no number), so quality is untouched; it only makes files manageable. Packing frames separately keeps random access and lets the player unpack several frames at once.
- Proof: RESULTS P2.9: identical PSNR with and without zlib; ratio 3.0x at n = 0 to 9.6x at (4,4); ZioTest round trips.
- Cost or limitation: The .cmp is still larger than the mp4, because the baseline has no motion prediction.
- Say it in the presentation: "zlib is lossless packing, not part of the codec's quality story; it just keeps the file to a usable size."
- Likely professor question: Why is your .cmp bigger than the mp4? / Answer: H.264 predicts each frame from the previous one and codes only the change, plus strong entropy coding. Our baseline codes every frame from scratch, as the brief asks.

### P2-L8: Frame table at the end of the file, and safe closing
- Date / window: Nov 11, Window B (03 D7; backfilled by Window C)
- Brief says: Brief is silent.
- The problem in plain words: To step backwards the player must jump to any frame. Also, if the encoder crashes halfway, the decoder must not show garbage.
- Options we considered:
  1. Read frames only in order : good simple file / bad step back means rereading from the start
  2. A footer with the byte position of every frame, written at the end : good jump to any frame in one read / bad the header must be patched after writing
- What we chose: A footer listing every frame's position. The header's frame count and footer position are written as 0 first and filled in when the file is closed.
- Why (plain words): A footer position of 0 means the encoder never finished, so the decoder gives a clear error instead of a broken picture. The table makes step back and seeking instant.
- Proof: CmpFormatTest ruleFooterOffsetZeroMeansUnfinished, threeFramesRoundTripIdenticallyWithAndWithoutZlib (random access out of order).
- Cost or limitation: 8 bytes per frame, negligible.
- Say it in the presentation: "The file ends with a table of where each frame starts, so the player can jump anywhere."
- Likely professor question: What happens if the encoder crashes? / Answer: The footer position is still 0, so the decoder says the file is unfinished. It never plays a half-written file.

### P2-L9: Block type stays 0 or 1
- Date / window: Nov 11, Window B (03 D7; backfilled by Window C)
- Brief says: Each block has a block_type, foreground or background (C5).
- The problem in plain words: Segmentation finds several separate moving regions. We could store which region each block belongs to, or just foreground or background.
- Options we considered:
  1. Store region number (1 to 255) : good richer / bad the decoder does not need it, not what the brief describes
  2. Store 0 (background) or 1 (foreground) : good exactly the brief / bad region colors must come from elsewhere
- What we chose: 0 or 1 in the file. Region colors exist only in the segmentation viewer.
- Why (plain words): The decoder only needs to know which quantizer to use. Keeping the file to the brief's binary state avoids confusion.
- Proof: CmpFormatTest blockTypeOf8x8IsItsMacroblockLabel (region 255 becomes 1), ruleBlockTypeMustBe0Or1.
- Cost or limitation: The decoder overlay can show foreground but not which region.
- Say it in the presentation: "The file stores one bit of meaning per block: foreground or background."
- Likely professor question: How does an 8x8 block know its type? / Answer: It sits inside exactly one 16x16 macroblock, and it copies that macroblock's label.

### P2-L10: Skipping empty rows in the inverse DCT
- Date / window: Nov 11, Window B (backfilled by Window C)
- Brief says: Brief is silent.
- The problem in plain words: The decoder was over its time budget. After quantization most coefficient rows are all zero, and computing with zeros is wasted work.
- Options we considered:
  1. Plain inverse DCT always : good simplest / bad slower
  2. Skip all-zero rows and columns : good faster on typical blocks / bad must prove it gives identical output
- What we chose: The decoder uses the zero-skipping inverse.
- Why (plain words): Same answer, less work. A test proves every output pixel is bit-for-bit identical.
- Proof: DctTest zeroSkipInverseIsBitIdenticalToPlainInverse (500 sparse blocks).
- Cost or limitation: Little gain at n = 0, where few rows are zero (the worst case in RESULTS P3.1).
- Say it in the presentation: "Most quantized rows are zero, so the inverse DCT skips them, with a test proving the picture is identical."
- Likely professor question: How do you know the shortcut is correct? / Answer: A test compares it with the plain inverse on 500 blocks and requires identical output. Any difference fails the build.

### P2-L11: Decoding straight into the cropped picture, safely from many threads
- Date / window: Nov 11, Window B (backfilled by Window C)
- Brief says: Decoder dequantizes, inverse transforms and displays (D2, D3).
- The problem in plain words: 1080 is not a multiple of 16, so frames are padded to 1088 rows. The padding must be removed, and later the player needs several threads decoding at once.
- Options we considered:
  1. Decode the padded frame, then copy out the visible part : good simple / bad an extra 25 MB copy per frame
  2. Write only visible pixels while decoding, with all working memory owned by the caller (scratch) : good one pass, safe in parallel / bad caller must give each thread its own scratch
- What we chose: Option 2. DecodeCore has no shared changing state.
- Why (plain words): It saves time on every frame and makes multi-threaded playback (P3) safe without locks.
- Proof: DecodeCoreTest manyThreadsDecodingAtOnceAgree (16 at once), nZeroRoundTripIsNearlyExactAndCropped; same file hash on 1 and 8 threads (RESULTS P2.9).
- Cost or limitation: None.
- Say it in the presentation: "The decoder removes the padding while it decodes, and several threads can decode different frames at once."
- Likely professor question: Why must decode be thread-safe? / Answer: One 1080p frame takes 72 ms on one core, more than the 33 ms budget. Only decoding several frames in parallel keeps up, which needs code with no shared changing state.

### P2-L12: One shared encoder routine
- Date / window: Nov 11, Window B, approved by PM (backfilled by Window C)
- Brief says: Brief is silent on code structure.
- The problem in plain words: Both the test harness and the real encoder must turn a frame plus labels into file bytes. Two copies of that code could drift apart.
- Options we considered:
  1. Put the code inside the test tool : good fast to write / bad the real encoder would copy it
  2. A shared FrameEncoder class : good one tested path for both / bad one more file
- What we chose: FrameEncoder: frame and macroblock labels in, file bytes out (pad, find block types, DCT per channel, quantize by type).
- Why (plain words): Whatever we tested with fake labels is exactly what the real encoder runs.
- Proof: FrameEncoderTest (5 tests), parallelGivesIdenticalBytes.
- Cost or limitation: None.
- Say it in the presentation: "The encoder we measured in testing is the same code the real encoder calls."
- Likely professor question: How do you know testing reflects the real encoder? / Answer: Both call the same FrameEncoder. Only the source of labels differs.

### P2-L13: How quality is measured (PSNR per layer)
- Date / window: Nov 11, Window B (backfilled by Window C)
- Brief says: Foreground should look better than background when n1 < n2 (D5). No metric is given.
- The problem in plain words: "Looks better" needs a number we can put in a table, and it must be split into foreground and background.
- Options we considered:
  1. Visual judgment only : good what the grader sees / bad not repeatable
  2. PSNR (peak signal to noise ratio, in decibels: higher means closer to the original) over all pixels : good standard / bad mixes the two layers
  3. PSNR separately for foreground and background pixels : good shows the layer effect directly / bad a layer can be empty in a frame
- What we chose: PSNR over R, G, B, computed overall and separately per layer (a pixel's layer is its block's type), reported as the mean over frames. An empty layer reports "not a number" instead of a fake value.
- Why (plain words): It turns "foreground is cleaner" into a measured gap, for example 48 dB versus 34 dB.
- Proof: PsnrTest (4 tests); RESULTS P2.9: (2,5) gives foreground 47.7 dB and background 34.5 dB, (5,2) reverses it.
- Cost or limitation: PSNR does not perfectly match what eyes see; we pair it with 1:1 crops.
- Say it in the presentation: "At n1 = 2, n2 = 5 the foreground scores 48 dB and the background 34 dB, and swapping them swaps the scores."
- Likely professor question: Why does swapping n1 and n2 swap the PSNR? / Answer: Each layer's quality depends only on its own step size. Swapping the steps swaps which layer gets the fine one.

### P2-L14: A dump tool in the brief's text layout
- Date / window: Nov 11, Window B (backfilled by Window C)
- Brief says: The suggested file layout is n1 n2, then block_type and 3 x 64 coefficients per block (C5).
- The problem in plain words: Our file is binary (P2-L6). The grader should still be able to check it against the suggested layout.
- Options we considered:
  1. Explain the binary layout only in the report : good no code / bad grader must trust us
  2. CmpDump tool that prints the header, frame table and any block in the brief's text layout : good proof on demand / bad one more tool
- What we chose: CmpDump, plus read-only reader methods for frame positions (the file layout did not change).
- Why (plain words): It shows, live, that our file holds exactly the brief's content.
- Proof: CmpDumpTest printsTheBriefsTextLayout (193 numbers per block).
- Cost or limitation: None.
- Say it in the presentation: "Here is our binary file printed in the exact layout the brief suggests."
- Likely professor question: Show me a background block. / Answer: CmpDump prints it: at n = 5 it is a DC value then almost all zeros, because the large step erased the fine detail.

### P2-L15: Testing compression before segmentation existed
- Date / window: Nov 10 to 11, PM + Window B (backfilled by Window C)
- Brief says: Brief is silent.
- The problem in plain words: Compression was built in parallel with segmentation, so real foreground labels did not exist yet.
- Options we considered:
  1. Wait for segmentation : good real labels / bad days of lost time
  2. Fake labels: a checkerboard of macroblocks, or all foreground, or all background : good tests everything now / bad numbers mirror too perfectly
- What we chose: Fake checkerboard labels in CodecHarness; re-measure with real labels after wire-up.
- Why (plain words): It let compression finish 5 days early and proved the layer effect before segmentation was done.
- Proof: RESULTS P2.6 and P2.9; CodecHarnessTest.
- Cost or limitation: With a checkerboard both layers hold similar content, so swapping n1 and n2 mirrors PSNR almost exactly. Real labels will not mirror exactly. Must re-measure after wire-up.
- Say it in the presentation: "We proved the compression with a fake checkerboard, then re-measured on real segmentation."
- Likely professor question: Why do your early numbers mirror so perfectly? / Answer: Checkerboard halves contain statistically similar scenery. With a real person versus a wall, the layers differ, so the swap reverses the order but not the exact numbers.
