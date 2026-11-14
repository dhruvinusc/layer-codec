# P4 Extra credit: decision log

Owner: proposed by Window C (Nov 14). Status: DESIGN PROPOSAL, nothing built. This file is a DECISION REQUEST for PM (00 section 5): it asks for a yes, a smaller yes, or a no before any code exists.

### P4-L1: Motion-compensated residual coding and zigzag + run-length (DECISION REQUEST)
- Date / window: Nov 14, Window C (proposal only)
- Brief says: Extra credit, only after everything else works: "motion-compensated prediction: encode the residual plus one motion vector per macroblock" (F1) and "a better coefficient scan order (zigzag) to help compression" (F2). The baseline without zigzag, intermediate representation or entropy coding is acceptable (C6). Extra credit must sit behind a flag and leave the default output byte-identical (00 section 3).
- The problem in plain words: Our baseline codes every frame from scratch, so the .cmp is 3x to 9.6x smaller than raw but still larger than the mp4 (P2-L7). Most of a frame is almost the same as the previous one, moved a little by the camera. Coding only the difference after that move, and storing zeros cheaply, should make files several times smaller without changing what the layers mean.
- Options we considered:
  1. A: full extra credit (F1 + F2): residual coding with one motion vector per 16x16 macroblock, predicted from the RECONSTRUCTED previous frame, an I frame (coded from scratch) every N frames, then zigzag + run-length : good the largest size gain, covers both extra-credit items / bad most code, breaks the decoder's frame-parallel speed trick (see risks), about 3.5 days
  2. B: only F2: zigzag + run-length on today's baseline blocks, as a new mode : good small, safe, every frame still independent so the P3 player keeps working unchanged / bad modest gain, covers one item only, about 1 day
  3. C: no extra credit : good all time goes to P1 quality, report and rehearsal / bad leaves possible marks unused
- What we chose: PROPOSED, not decided: B first (Nov 19 to 20), then A only if P1 and wire-up are done and a checkpoint on Nov 22 says there is time. PM decides.
- Why (plain words): B is cheap, low risk and measurable in a day, and its run-length coder is reused by A anyway. A carries real risk to real-time playback, and P1 is the critical path with thin margin (due Nov 16), so A should only start once the required parts are safe.
- Proof: none yet (design). Measurements planned below.
- Cost or limitation: see Risks and Effort below.
- Say it in the presentation: "Our extra credit codes only what changes: each block is predicted from where it was in the previous decoded frame, and the small leftover is scanned low to high frequency so the zeros collapse into a few bytes."
- Likely professor question: Why predict from the reconstructed frame, not the original? / Answer: The decoder only has its own reconstructed frames. If the encoder predicted from the originals, every frame's small error would be built on by the next and grow over time (drift). Predicting from the same reconstruction the decoder has keeps both in step.

#### Design details (for PM review)

1. Switching it on, and keeping the baseline byte-identical
   - CLI: `myencoder input.mp4 n1 n2 [--mode ec]`, off by default (00 section 3). Option B would be `--mode rle`.
   - File: the mode bits already reserved in the .cmp flags (03 D7, bits 1 to 3): 0 = baseline (today), 1 = run-length only (B), 2 = motion-compensated (A). The 48-byte header and the footer stay exactly the same; the reader already rejects unknown modes.
   - Byte-identical baseline: the mode 0 writer path is not edited; new code lives in new classes and is reached only when mode is not 0. A golden test pins today's baseline output: the SHA-256 of a fixed CodecHarness run (for example SAL first 60 frames at (2,5), sha `1da794c0...` from P3.0) must not change, plus the existing CmpFormatTest round trips.
2. Per-frame payload for mode 2 (A), after the frame's existing length prefix
   - 1 byte frame type: 0 = I frame, 1 = P frame (predicted).
   - P frame only: one motion vector per 16x16 macroblock, 2 signed bytes (dx, dy), scan line order. Range +/-127 covers video2's +/-32. 1080p: 120 x 68 = 8,160 macroblocks = 16 KB per frame before zlib (vectors are highly repetitive under a camera pan, so zlib shrinks them a lot).
   - Then, per 8x8 block in scan line order: block_type (unchanged meaning, picks n1 or n2), then for R, G, B the quantized RESIDUAL coefficients (I frame: the coefficients themselves) in zigzag order, run-length coded (below).
   - The whole payload is still zlib-wrapped per frame as today, so random access by footer offset still works.
3. Prediction
   - Motion vectors: reuse Window A's per-macroblock vectors (Y plane, previous ORIGINAL frame, D12 sign convention). No new search.
   - Reference: the encoder keeps its own decoded copy of the previous frame (it runs the decoder's dequantize + inverse DCT), and predicts block (x, y) from (x + dx, y + dy) in that copy, for R, G and B. Residual = current minus prediction, then the same 8x8 DCT and 2^n quantization by layer as the baseline.
   - I frame every N frames (N stored per frame by the frame type, so the decoder needs no extra header field). Proposed N = 8 (reason under Risks), and frame 0 is always an I frame.
4. Zigzag + run-length (mode 1 and mode 2)
   - Zigzag: the standard JPEG order of the 64 coefficients, low to high frequency, so after quantization the zeros sit together at the end.
   - Run-length per 8x8 channel block: pairs (number of zeros before the next nonzero value: 1 byte, value: 2 bytes), ended by an end-of-block marker (0xFF). An all-zero block costs 1 byte instead of 128.
   - zlib stays on top (lossless, already measured cheap).
5. Expected gains (ESTIMATES, to be measured, not promises)
   - B alone: zlib already squeezes out much of the repeated zeros, so the extra gain is likely modest: an estimated 1.2x to 1.5x smaller than today's zlib baseline (today SAL (2,5) = 1.79 MB per frame).
   - A: on these pans most background blocks are well predicted, so at n2 = 5 most residual blocks quantize to all zeros. Rough estimate for N = 8: P frames 3x to 5x smaller than I frames, giving about 2.5x to 3.5x smaller files than the baseline overall. Worst on video2 (fast tracking shot, parallax) and on uncovered background.
   - Quality: at the same n1 and n2, per-layer PSNR should stay within about 1 dB of the baseline, since the same quantizer applies to the residual and predicting from the reconstruction prevents drift. The layer effect (foreground cleaner when n1 < n2) is kept, because block_type still chooses the quantizer per block.
   - Measured and reported as a table per clip: MB per frame, ratio vs baseline, PSNR all / foreground / background, encode and decode ms per frame (RESULTS "P4").
6. Risks
   - Real-time playback (biggest): P frames depend on the previous frame, so the P3 trick of decoding several whole frames at once (P3-D1) no longer works inside a run of P frames. Mitigation: decode whole groups (an I frame plus its P frames) in parallel, one group per worker, keeping frames packed. With N = 8 and 4 workers that is at most 32 packed frames in flight (about 270 MB at 1080p). A larger N compresses better but needs more memory or loses parallelism, hence N = 8. Residual blocks are mostly zero, so the zero-skip inverse DCT also gets faster.
   - Step back: going back inside a group needs the group's I frame and the P frames up to the target: at most N - 1 = 7 extra decodes; the 30-frame cache still covers recent frames.
   - Drift bugs: an encoder whose reconstruction differs from the decoder's by even one rounding step drifts. Mitigation: the encoder calls the same DecodeCore functions, and a test compares encoder-side and decoder-side reconstructions bit for bit over 60 frames.
   - Dependencies: needs P1 labels and wire-up first (A also needs P1's vectors). Touches closed P2 files (CmpFormat reader dispatch, FrameEncoder) and Window A's EncoderMain (the flag): each change needs its own small DECISION REQUEST, or PM assigns those files to the P4 window.
   - Schedule: P1 is due Nov 16 with thin margin and P5 freezes Nov 27 (02 slip policy: P4 shrinks first).
   - Explainability: Dhruvin must be able to explain drift, groups of pictures and run-length at the whiteboard; budget Tutor time.
7. Effort estimate (one window, sandbox for code and tests, Mac for timing)
   - B: format mode 1, zigzag table, run-length writer and reader, tests, sizes table: 1 day.
   - A on top of B: frame types and vector payload 0.5 day; encoder prediction with reconstruction + drift test 1 day; decoder + group-parallel pipeline + player integration 1 day; Mac measurements and report table 0.5 day; decision log and Tutor notes 0.5 day: about 3.5 days.
   - Total A + B: about 4.5 days inside the Nov 19 to 26 window, with a go/no-go checkpoint for A on Nov 22.

#### Questions for PM (the decision request)
1. Approve B (mode 1, zigzag + run-length) for Nov 19 to 20? 
2. Approve A conditionally, with a go/no-go on Nov 22, or not at all?
3. Which window builds P4 (Window C, or a new P4 window), and may it change CmpFormat (reader dispatch for modes 1 and 2), FrameEncoder and EncoderMain's flag parsing?
4. Accept N = 8 for the I-frame interval (memory and parallel decode), or prefer a larger N for size?
