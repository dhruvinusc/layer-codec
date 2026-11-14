# P3 END-OF-PART REPORT (DRAFT, Window C)

Status: DRAFT, Nov 14, 2026. Final version after the three pending Mac checks marked PENDING below. Format: 00 section 10.

```
END-OF-PART REPORT  P3  Nov 14, 2026 (draft)

Checklist IDs proven:
  A3 mydecoder decodes and renders: Mac runs RESULTS "P3.3" runs 1 to 4, "P3.4/P3.4b Mac checks";
     DecoderMain.play (codec/decoder/DecoderMain.java).
  A4 Decoder needs only the .cmp: every playback, bench and error run uses the .cmp alone; header
     carries size, padded size, fps, frame count, n1, n2 (03 D7); the original mp4 is optional and
     only for side by side. Tests: CmpFormatTest (17), DecoderMainErrorsTest (10).
  D1 Parses n1, n2, block types, coefficients: CmpFormat.Reader + DecodeCore; CmpFormatTest rule*,
     DecodeCoreTest returnsTheBlockTypeMap.
  D2 Dequantizes with n1 or n2 by block_type: DecodeCoreTest blockTypeChoosesTheExponent,
     storedThreeRebuildsAs12InForegroundAnd96InBackground.
  D3 Inverse DCT, reconstruct, display: DecodeCore (zero-skip IDCT, bit-identical, DctTest),
     DecodePipelineTest everyFrameIdenticalToSerialDecode, Player + Display on the Mac.
  D4 Plays at the original frame rate: RESULTS P3.3 run 2 (all clips within 0.6%, full video2
     12.77 s vs 12.77 s) and run 4 (SAL 200 frames, 0 drops, within 0.13%); PlayerTest (8, fake clock).
  D5 Foreground cleaner when n1 < n2, reversed when swapped: images docs/img/p3_SAL_n2_5_side_1to1.png,
     p3_SAL_n5_2_side_1to1.png (decoder's own 1:1 side-by-side view); PSNR RESULTS P2.9 (fg 47.7 dB vs
     bg 34.5 dB at (2,5), reversed at (5,2)). Fake checkerboard labels: re-check with real labels (P3.R).
  E1 Own display code for input and output: Display (own bilinear scaler P0-D8, packed path, view logic),
     DisplayScalerTest, DisplayViewTest (10); original read by OriginalVideo.
  E2 Play, pause, step (and step back): Mac keys check Nov 12 ("smooth"); PlayerTest
     pauseStepAndStepBackMoveOneFrameAndStopTheClock; FrameCacheTest (100 back to 50).
  E3 Output compared against the original: side by side + 1:1 zoom + overlay (4aba75b), Mac view check
     Nov 13 (screenshot); with the original open 0 drops at 30.0 fps (RESULTS, 2127da9).

Checklist IDs partial or missing:
  E4 Encode + decode under 10 min: decode side is faster than real time (pipeline 40.7 to 60.7 fps
     at 1080p); needs real myencoder output after wire-up (P3.R).
  D5 final check with real segmentation labels after wire-up (P3.R).
  PENDING Mac checks before this report is final:
    1. Step back 35 frames with the original open (both rings, reopen past 30).
    2. run4.sh on a quiet Mac (no build window busy, after D9): P3.4b verdict, whole-clip late under 2%.
    3. P3.7 full-length SAL at (0,0) and (2,5), first 100 vs last 100 frames (thermal): blocked on D9.
  SPEC_CHECKLIST.md boxes for A3, A4, D1 to D4, E1 to E3 not yet ticked: to tick with the evidence
  above once PM accepts this report.

Key results (Mac, MacBook Air 8 cores, fake checkerboard labels):
  - One 1080p frame on one core: 54.1 ms at (2,5), 71.9 ms at (0,0); budget 33.3 ms.
  - Frame-parallel pipeline: (0,0) 6 workers 21.0 ms = 47.7 fps, 4 workers 24.6 ms = 40.7 fps;
    (2,5) 4 workers 16.5 ms = 60.7 fps. Bit-identical to serial decode. 1 worker = serial (no overhead).
  - Playback (run 4, fair order): 0 drops, durations within 0.07 to 0.13%; late frames 4 workers 3.5%
    average (frames 100..199: 0 to 1), 6 workers 5.75%. Late frames = JIT warm-up in the first 2 s.
  - Step-back cache: 30 packed frames = 249.8 MB at 1080p; 100 back to 50 pixel-identical
    (29 hits + 21 re-decodes).
  - Side by side with the original (background reader): 0 dropped, 1 late, 30.0 fps (+0.14%).
  - Tests: 175/175 green at 914e8f1 (sandbox), zero warnings.

Decisions made (repo DECISIONS.md P3-D1..P3-D13; decision log P3-L1..P3-L15):
  frame-parallel decode with serial workers (P3-D1, L2); ordered slots + bounded window (D2, L3);
  early --bench (D3, L4); 30-frame packed cache (D4, L6); player drop-to-newest rule (D5, L7);
  packed display path + status line (D6); pre-roll (D7, L8); 4 workers default, confirmed by a fair
  test (D8, L5/L9); view keys inside Display (D9, L11); original reader (D10, L11, L15);
  paint warm-up (D11, L12); overlay from block_type (L13); exit codes and one-line errors (D12, L14);
  background original reader (D13, L15); recovery from the Nov 12 incident (L10).

DECISION REQUESTS pending:
  - P4 extra-credit design (docs/decision-log/P4-extra-credit.md, P4-L1): options A / B / C.
  - Conditional: -Xms1g in the mydecoder launchers if the quiet run4 shows whole-clip late over 2%
    (approved conditionally Nov 13).

Doc change requests:
  1. 03 D8 / H3: list the hidden, off-by-default flags --bench, --workers N, --trace file.csv.
  2. 02 file ownership: add OriginalVideo.java to Window C (approved Nov 13, P3.5c).
  3. 03 D8: default decode workers is 4 = max(2, min(cores-2, cores/2)), not cores-2 (P3-D8).
  4. Demo script (P5): one warm-up run before showing anything (first run after a build hiccups).

Known issues and risks:
  - First run after a build or a long idle hiccups (JIT warm-up, disk cache); plan a warm-up run.
  - Two 1.4 to 1.8 s whole-process freezes seen on Nov 13 (GC max 7.7 ms, not our code): most likely
    memory swapping on a 94% full disk. Freeing disk (D9) is the fix to try first.
  - Whole-clip late % is 0.5 to 5.5% (steady state 0 to 1%): P3.4b effect not yet measured cleanly.
  - With the original open, a step back past 30 frames rereads the mp4 from frame 0 (seconds near the
    end of SAL).
  - On a Retina screen "1:1" means one video pixel per screen point (2x2 physical pixels).
  - All P3 numbers use fake checkerboard labels; re-measure on real myencoder output (P3.R).

Explain-back: written explain-backs moved to the Tutor at Dhruvin's request (06 D11). Unaided so far:
  frame ordering by numbered slots (P3.1 prediction). Dhruvin found three real issues himself:
  45.6 ms was an 8-core number (led to P3-D1), the 60-frame clips were all warm-up, and he asked
  whether drops were really counted (verified frame by frame). To review: step back before the
  cache, packed pixels and 100 back to 50, drop vs slow down, pre-roll, 4 vs 6 workers, 1:1 zoom,
  overlay without segmentation code, background reader.

Commit hash: 2127da9 (last P3 commit); this draft adds one more.
TICKS: P3.0 to P3.6, P3.4c, P3.5c; P3.4b (verdict pending); P3.7 blocked (D9)
```
