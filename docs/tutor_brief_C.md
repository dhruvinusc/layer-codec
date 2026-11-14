# Tutor brief from Window C (P2 compression, P3 decoder + player, P4-B extra credit)

For the Tutor (Instructor) chat, Nov 14. Full reasoning per decision: `docs/decision-log/P2-compression.md`,
`P3-decoder-player.md`, `P4-extra-credit.md` (each entry has "Say it in the presentation" and a likely
professor question). Numbers: `docs/RESULTS.md`. Demo: `presentation/demo_script_decoder.md`.
Status: P2 done, P3 signed off Nov 14, P4-B built (P4-A decided Nov 22). Dhruvin skipped written
explain-backs in the build window on purpose; they are owed here (06 items D7 and D11).

## 1. Decision points to teach (one line each: what we chose, the number that proves it)

### P2 Compression (Window B built it; Window C logged it)
- P2-L1 DCT: the class formula, level shift by 128. Flat block of 200: DC = 8 x 72 = 576.
- P2-L2 Coefficient order: plain row order in the baseline; zigzag is extra credit.
- P2-L3 All loss in one place: only quantization loses information; n = 0 still gives 59 dB.
- P2-L4 Rounding: round(c / 2^n), halves away from zero; about half the error of truncation.
- P2-L5 16-bit coefficients: level shift keeps DC between -1024 and 1016.
- P2-L6 Binary file with a 48-byte header: the brief's text layout has no size or fps; text SAL > 15 GB.
- P2-L7 zlib per frame: lossless, 3x to 9.6x; the .cmp is still bigger than the mp4 (no prediction).
- P2-L8 Footer frame table + "unfinished" detection (footer offset 0).
- P2-L9 block_type stays 0/1: an 8x8 block copies its 16x16 macroblock's label.
- P2-L10 Inverse DCT skips all-zero rows: proven bit-identical.
- P2-L11 Decoder crops while decoding and is thread-safe (needed by P3).
- P2-L12 One shared encoder routine (FrameEncoder).
- P2-L13 PSNR per layer: (2,5) foreground 47.7 dB vs background 34.5 dB, swap reverses it.
- P2-L14 CmpDump prints our binary file in the brief's text layout.
- P2-L15 Fake checkerboard labels let compression be tested before segmentation existed.

### P3 Decoder and player
- P3-L1 Short test files first (disk was nearly full).
- P3-L2 Several whole frames decode at once: one frame = 52 to 72 ms on one core vs a 33 ms budget;
  4 workers = 61 fps at (2,5), 46 fps at (0,0).
- P3-L3 Numbered slots keep frames in order; workers may run only 8 frames ahead (about 200 MB).
- P3-L4 A hidden --bench mode measured the decoder before the player existed.
- P3-L5 / P3-L9 Workers: 6 looked fastest, the real player showed 4 paints on time better; the first
  three comparisons were unfair (run order mattered more than the setting), so the final test
  alternated order and cooled the laptop first.
- P3-L6 Step-back cache: last 30 frames, packed (one int per pixel), 250 MB; beyond that, jump via the
  footer table. 100 back to 50 = 29 cache hits + 21 re-decodes, pixel-identical.
- P3-L7 Late frames: skip to the frame that should be on screen, never slow down; count drops and late.
- P3-L8 Pre-roll: start the clock only after frame 0 is shown and 6 frames are ready (first run was
  9 to 17% short because start-up counted as lag).
- P3-L10 Recovering from the Nov 12 duplicate-chat incident (one owner, rebuild from the record).
- P3-L11 Side by side + 1:1 zoom: both halves show the same spot, unscaled (scaling hides artifacts).
- P3-L12 Paint warm-up during pre-roll (Java compiles hot code while it runs: JIT).
- P3-L13 Overlay from the block_type byte: no segmentation code in the decoder.
- P3-L14 One-line errors and exit codes (unfinished file, wrong file, wrong version).
- P3-L15 The original video is read ahead on its own thread: first version dropped 13 to 45% of
  frames, now 0 drops at 30 fps.
- Final evidence: full SAL plays 14.58 s vs 14.57 s, 0 drops at (2,5) and (0,0); first and last
  100 frames equally smooth (no heat slowdown on the fanless Air).

### P4 Extra credit
- P4-L1 Options A (motion-compensated residuals), B (zigzag + run-length), C (none): B approved,
  A decided Nov 22. Why predict from the RECONSTRUCTED previous frame: drift.
- P4-L2 Mode 1 built: 1.18x to 1.23x smaller (zlib had already removed many zeros), decodes 25%
  faster (76 vs 61 fps), identical pictures; a golden SHA test proves the default output unchanged.

## 2. Open decision points the Tutor should know about
- P4-A go/no-go on Nov 22 (only if P1 and wire-up are done).
- P3.R after wire-up: re-measure on real files; D5 (layer effect with real labels) and E4 (encode +
  decode under 10 minutes) are still partial.
- All P3 images and numbers use fake checkerboard labels; regenerate slide images from real files.

## 3. Question bank (predictions and explain-backs owed by Dhruvin)
1. Why can the player keep up when one frame takes 52 to 72 ms but the budget is 33 ms?
2. How do frames stay in order when several threads decode them?
3. Before the cache existed, what happened when you stepped back from frame 30, and why was it slow?
4. Why packed pixels? What happens to frames 70 to 50 when you step back from 100?
5. Why drop a late frame instead of slowing down?
6. Why pre-roll, and why is warming up the paint path not cheating the measurement?
7. Why did 4 workers beat 6 on this laptop? Why were the first comparisons unfair?
8. Predict where the decoded half at (2,5) looks worse than the original at 1:1, and where the same.
9. Why does 1:1 zoom show artifacts the fitted view hides?
10. How does the overlay know foreground without segmentation code?
11. Why do both zoomed halves show the same region?
12. Step back 35 frames with the original open: what happens in each 30-frame memory?
13. What does the decoder print for an unfinished .cmp, and how does it know?
14. Why did reading the original on the player thread drop frames, and how does a reader thread fix it?
15. Zigzag: why does visiting low to high frequency help run-length coding?
16. Why was the mode-1 size gain only about 20%, and why is decoding faster?
17. P4-A: what is drift, and why must the encoder contain a decoder?
18. P2 (owed, D7): DC of a flat block of 200; why round not truncate; why zlib is allowed; why the
    .cmp is bigger than the mp4; why swapping n1 and n2 swaps the PSNR.
