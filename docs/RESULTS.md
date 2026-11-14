# Results

Every number here comes from a Release-equivalent run on Dhruvin's Mac (launcher, after JIT warm-up) unless marked "sandbox".

## Environment
| Item | Value |
|---|---|
| Machine | MacBook Air, Apple Silicon, macOS 14.2.1 |
| JDK | Temurin 21 |
| FFmpeg | 7.1 |

## P0 probe (VideoInfo per clip)
Sandbox run (FFmpeg 4.4), Nov 9. Matches Project doc 05 for every clip. Frames counted by reading to end of stream.

| Clip | Upright size | Padded | Frames counted (tag) | fps | Rotation applied (cw) | Pipe read speed (sandbox) |
|---|---|---|---|---|---|---|
| video1 | 480x848 | 480x848 | 518 (518) | 30/1 | 0 | 724 fps |
| video2 | 480x848 | 480x848 | 383 (383) | 30/1 | 90 | 403 fps |
| Stairs | 1920x1080 | 1920x1088 | 346 (346) | 30/1 | 180 | 91 fps |
| SAL | 1920x1080 | 1920x1088 | 437 (437) | 30/1 | 180 | 95 fps |

Upright check: `docs/img/p0_upright_thumbnails.jpg` (frame 0 of each clip; Stairs text reads correctly, so 180 is a true rotation, not a mirror; sky is blue, so channel order is RGB).

## P0 playback (ProbeVideo --play, Mac, uninterrupted first pass)
| Clip | Frames | Measured | Clip duration | Error | Late frames (> half a period) | Window scale |
|---|---|---|---|---|---|---|
| video2 | 383 | 12.74 s | 12.77 s | -0.2% | 4 (1.0%) | 0.92 |
| video2 (rerun) | 383 | 12.75 s | 12.77 s | -0.1% | 0 (0.0%) | 0.92 |
| SAL, Java2D scaling (before P0-D8) | 437 | 20.83 s | 14.57 s | +43.0% | 437 (100%) | 0.72 |
| SAL, own scaler (P0-D8), run 1 | 437 | 14.55 s | 14.57 s | -0.1% | 2 (0.5%) | 0.72 |
| SAL, own scaler (P0-D8), run 2 | 437 | 14.53 s | 14.57 s | -0.2% | 6 (1.4%) | 0.72 |

Per-frame cost for SAL with P0-D8 (Mac): decode 5.5 to 5.6 ms, pack+scale 1.6 ms, paint 5.8 to 8.1 ms, total about 13 to 15 ms of the 33.3 ms budget.

## P0 speed gate (SpeedGate on SAL frames 100 to 129, after JIT warm-up)
| Test | Mac (8 cores) | Sandbox (4 cores) | Target | Verdict |
|---|---|---|---|---|
| (a) Display 300 x 1080p at 30 fps, late frames | 2 (0.7%) | no screen | < 2% | PASS |
| (b) 16x16 SAD, random positions | 139.6 ns | 166.1 ns | report | full search +/-16 would be 1.24 s per 1080p frame, +/-32 4.8 s: hierarchical search is required (D2) |
| (c) Naive separable 8x8 IDCT, 1920x1088 x 3 channels (97,920 blocks) | 77.2 ms 1 thread, 15.3 ms all cores | 87.2 / 21.8 ms | < 25 ms all cores | PASS |
| (d) One frame in D7 layout, zlib level 1 (fake labels, n1=2, n2=5) | 12.57 MB to 1.22 MB (10.3x); deflate 30.4 ms, inflate 15.3 ms | 1.27 MB (9.9x); 46.6 / 15.3 ms | report | zlib is cheap; ratio beats the 7x estimate in Project doc 05 |

Decoder budget at 1080p, serial: inflate 15.3 + IDCT 15.3 (all cores) + paint 6 to 8 = 37 to 39 ms, over 33.3 ms. The decoder must pipeline (decode-ahead threads, D8); per-frame zlib chunks let inflate run in parallel too.

## P1 segmentation

### P1.1 full search reference (sandbox, Nov 10; Mac timings come with P1.3)
One frame pair per clip, Y plane, padded frame. "At range edge" = share of blocks whose best vector has |dx| or |dy| equal to R, a sign the true motion is outside the window. Time is the second run (after JIT warm-up), single thread, early exit on SAD.

| Clip, frame | R | Blocks | ms/frame | Mean MAD | At range edge | Most common vectors (dx,dy = count) |
|---|---|---|---|---|---|---|
| video2, 100 | 16 | 1590 | 67 | 5.76 | 29.3% | -16,-3 = 168; -14,-3 = 125; -14,-2 = 87 |
| video2, 100 | 32 | 1590 | 212 | 4.08 | 2.2% | -14,-3 = 125; -14,-2 = 86; -16,-3 = 71 |
| SAL, 200 | 16 | 8160 | 382 | 3.08 | 3.3% | 7,0 = 3048; 6,0 = 1069; 8,0 = 810 |
| Stairs, 170 | 16 | 8160 | 328 | 3.36 | 1.2% | -6,0 = 918; -5,0 = 905; -6,1 = 345 |
| video1, 250 | 16 | 1590 | 61 | 1.84 | 10.1% | 0,0 = 253; 6,1 = 171; 5,1 = 75 |

Reading: SAL's camera pans 6 to 8 px per frame and Stairs 5 to 6 px, matching doc 05. video2 at R=16 has 29% of blocks pinned at the window edge; at R=32 that drops to 2.2% and mean MAD falls from 5.76 to 4.08, so +/-32 is needed there (D2). Early exit makes full search about 3x faster than the P0 speed gate estimate (1.24 s), but it is still the reference, not the default.

| Clip | ME ms/frame full | ME ms/frame hier | Agreement within 1 px | Camera model mix | Mean inlier ratio | Mean foreground % | Regions/frame |
|---|---|---|---|---|---|---|---|

## P2 compression (Window B)

### P2.6 CodecHarness first real-clip runs (sandbox, Nov 11; correctness only, timings NOT authoritative, Mac numbers come in P2.9)
Fake checkerboard labels (half the macroblocks foreground) unless noted. PSNR is the mean of per-frame PSNR over R, G, B.

| Clip, frames | n1, n2 | Stored MB/frame (zlib) | Ratio vs raw | PSNR all / fg / bg (dB) |
|---|---|---|---|---|
| video2, 20 | 0, 0 | 1.07 | 2.30x | 59.19 / 59.19 / 59.18 |
| video2, 20 | 2, 5 | 0.46 | 5.35x | 35.53 / 47.47 / 32.67 |
| video2, 20 | 5, 2 | 0.46 | 5.36x | 35.55 / 32.68 / 47.47 |
| SAL, 6 | 0, 0 | 4.91 | 2.56x | 59.00 / 58.99 / 59.00 |
| SAL, 6 | 1, 7 | 2.24 | 5.62x | 29.82 / 52.23 / 26.82 |

- n = 0 loses only rounding (59 dB, H2 target >= 45). Swapping n1 and n2 swaps the fg/bg PSNR almost exactly (47.47 / 32.67 vs 32.68 / 47.47): early D5 evidence.
- The near-exact PSNR mirror is a checkerboard artifact: both halves of the checkerboard hold statistically similar content, so swapping n1 and n2 swaps the numbers almost perfectly. With real labels (moving person vs scenery) the two layers differ in content, so the swap reverses which layer is cleaner but the numbers will not mirror exactly.
- zlib ratio depends on the foreground share. SAL (2,5), 3 frames: all-fg 4.39x, checker 6.31x, all-bg 13.16x. The P0 speed gate's 10.3x used 1 in 7 blocks foreground, which fits between these; no contradiction.
- Images: `docs/img/p2_SAL_n1_7_checker_crop1to1.png` (1:1, original | decoded: alternate macroblocks smeared at n=7, sharp at n=1), `docs/img/p2_video2_n2_5_checker_crop1to1.png`, `docs/img/p2_video2_n5_2_checker_crop1to1.png`, `docs/img/p2_video2_n2_5_checker_half.png` (side by side at half scale). At half scale the difference is barely visible, which supports the 1:1 zoom in D8.
- Determinism: the synthetic end-to-end test runs the harness twice and checks equal SHA-256 (`CodecHarnessTest`).

### P2.9 Mac measurements (Nov 11, MacBook Air M-series, 8 cores, JDK 21, via `bash out/p29.sh`)
CodecHarness, fake checkerboard labels (half the macroblocks foreground), zlib level 1, 8 threads unless noted. video2 = first 120 frames (480x848), SAL = first 60 frames (1920x1080, padded 1088). Times are per frame and exclude reading the mp4. Raw output: `out/p29.txt`.

| Clip | n1, n2 | Stored MB/frame | Ratio | Encode ms | Decode ms | PSNR all / fg / bg (dB) |
|---|---|---|---|---|---|---|
| video2 | 0, 0 | 0.81 | 3.03x | 22.4 | 8.6 | 59.47 / 59.47 / 59.47 |
| video2 | 2, 5 | 0.35 | 6.99x | 15.1 | 6.5 | 37.22 / 48.14 / 34.39 |
| video2 | 5, 2 | 0.35 | 7.00x | 12.9 | 7.5 | 37.23 / 34.40 / 48.14 |
| video2 | 1, 7 | 0.38 | 6.37x | 13.7 | 6.7 | 29.72 / 52.76 / 26.73 |
| video2 | 4, 4 | 0.26 | 9.38x | 10.9 | 5.6 | 38.80 / 38.80 / 38.79 |
| SAL | 0, 0 | 4.21 | 2.98x | 136.7 | 45.6 | 58.97 / 58.97 / 58.97 |
| SAL | 2, 5 | 1.79 | 7.00x | 71.6 | 30.8 | 37.29 / 47.74 / 34.48 |
| SAL | 5, 2 | 1.79 | 7.03x | 66.0 | 31.7 | 37.29 / 34.48 / 47.74 |
| SAL | 1, 7 | 2.01 | 6.26x | 70.9 | 31.4 | 29.81 / 52.21 / 26.81 |
| SAL | 4, 4 | 1.31 | 9.59x | 53.6 | 27.2 | 38.79 / 38.80 / 38.79 |
| SAL, 1 thread | 2, 5 | 1.79 | 7.00x | 113.0 | 52.4 | same as 8 threads |
| SAL, `--raw` | 2, 5 | 12.57 | 1.00x | 33.5 | 16.5 | same as zlib |

What the numbers say
- Layer effect holds at both sizes: (2,5) gives fg about 48 dB vs bg about 34 dB; (5,2) reverses it; (1,7) widens the gap to about 26 dB. (The near-exact mirror is the checkerboard artifact noted in P2.6.)
- n = 0 loses only rounding: 59 dB on both clips (H2 target >= 45 dB).
- Determinism: SAL (2,5) has the same SHA-256 (`5fb928af...`) with 8 threads and with 1 thread, so threading never changes the file.
- zlib is lossless (raw and zlib runs give identical PSNR) and worth 3x (n=0) to 9.6x (4,4); size falls as n rises.
- zlib cost per 1080p frame, from the raw vs zlib rows: deflate about 38 ms (71.6 - 33.5), inflate about 14 ms (30.8 - 16.5). Both are single-threaded per frame, which is why 8 threads only speed one frame up 1.6 to 1.7x.
- Decoder budget (03 D8): one 1080p frame decodes in 27 to 32 ms at typical settings but 45.6 ms at n = 0, against 33.3 ms at 30 fps, before painting. Single-frame decoding cannot hold real time in the worst case: P3 must decode several frames ahead in parallel (frame-level parallelism also parallelizes inflate).
- Encode budget: worst case SAL n=0 at 136.7 ms/frame is about 60 s for all 437 frames, plus segmentation. Comfortably inside the 10-minute limit.
- Images (Mac run, frame 30 of SAL, 1:1, original | decoded): `docs/img/p29_SAL_n0_0_crop1to1.png`, `p29_SAL_n2_5_crop1to1.png`, `p29_SAL_n5_2_crop1to1.png`, `p29_SAL_n1_7_crop1to1.png`, `p29_SAL_n4_4_crop1to1.png`; video2 side by side `docs/img/p29_video2_n2_5_half.png`.

## P3 decoder (Window C)

### P3.1 DecodePipeline bench (Mac, Nov 12, 8 cores, JDK 21, `./mydecoder <cmp> --bench --workers 1,2,4,6`)
Test files from CodecHarness (fake checkerboard labels), SAL first 60 frames, 1920x1080. Serial = one core, `DecodeCore.decode`, 30 frames after a warm-up pass. Pipeline = full 60 frames, consumer pulls as fast as it can (no display).

| File | Serial 1 core | 1 worker | 2 workers | 4 workers | 6 workers (default) |
|---|---|---|---|---|---|
| SAL (0,0), worst case | 71.9 ms | 72.1 ms, 13.9 fps | 41.0 ms, 24.4 fps | 24.6 ms, 40.7 fps | 21.0 ms, 47.7 fps (1.59x real time) |
| SAL (2,5) | 54.1 ms | 53.9 ms, 18.6 fps | 31.4 ms, 31.9 fps | 16.5 ms, 60.7 fps | 17.0 ms, 58.9 fps (1.96x real time) |

What the numbers say
- Single-core n=0 decode is 71.9 ms, about 1.6x the 8-core 45.6 ms in P2.9: one frame alone is more than twice the 33.3 ms budget.
- The pipeline turns that into 21.0 ms per frame at n=0 with 6 workers (47.7 fps), so the worst case holds 30 fps with 37% headroom; (2,5) runs at about 2x real time.
- Scaling is near-linear to 4 workers (2.9x at n=0, 3.3x at (2,5)), then flattens: 6 workers add 17% at n=0 and nothing at (2,5). The Air likely has 4 performance and 4 efficiency cores, plus shared memory bandwidth (each 1080p frame is about 25 MB of int planes). Default stays max(2, cores-2) = 6 because it is best at n=0, the case that needs it.
- Peak buffered stayed 1 to 6 (capacity 8): with no display the consumer drains as fast as workers produce. Real buffering is measured in P3.3 with the player.
- Pipeline at 1 worker equals serial (72.1 vs 71.9 ms), so the pipeline adds no measurable overhead.

### P3.3 Player, Mac run 1 (Nov 12, BEFORE the pre-roll fix; kept for honesty)
`./mydecoder <file> [--workers N]`, uninterrupted first pass. Short test files (fake checkerboard labels).

| File | Workers | Dropped | Late | Played vs clip | Mean wait for a frame |
|---|---|---|---|---|---|
| video2 (2,5), 120 f | 6 | 19 (15.8%), all in frames 0..19 | 3 | 3.33 s vs 4.00 s (-16.8%) | 0.8 ms |
| SAL (2,5), 60 f | 6 | 12 (20.0%) | 16 (26.7%) | 1.76 s vs 2.00 s (-12.1%) | 10.1 ms |
| SAL (2,5), 60 f | 4 | 4 (6.7%) | 5 (8.3%) | 1.81 s vs 2.00 s (-9.3%) | 1.9 ms |
| SAL (0,0), 60 f | 6 | 8 (13.3%) | 20 (33.3%) | 1.78 s vs 2.00 s (-11.2%) | 5.6 ms |
| SAL (0,0), 60 f | 4 | 5 (8.3%) | 7 (11.7%) | 1.81 s vs 2.00 s (-9.5%) | 4.0 ms |

Diagnosis
- Bug, not speed: the clock started before frame 0 reached the screen. Window creation, the first cold decode and the first paint (about 0.6 s) counted as falling behind, so the first frames were dropped and the measured time was short by exactly that amount (video2: 19 frames = 0.63 s; 4.00 - 0.63 = 3.37 s, measured 3.33 s). video2's last 100 frames had 0 drops at 30.4 fps, and its mean wait was 0.8 ms: decoding was never the problem.
- Fix (P3-D7): pre-roll. Show frame 0, wait until frames 1..6 are decoded (at most 1 s), then start the clock. Test `slowStartIsAbsorbedByPrerollNotCountedAsDrops` (0.6 s cold start: 0 drops, 4.00 s).
- 4 vs 6 workers: 6 workers had 2.5x to 3x more late frames on SAL. Likely cause: the display scaler runs its rows on Java's shared thread pool, which competes with 6 decode workers for 8 cores (4 of them efficiency cores). Re-measured after the fix before deciding the default.

### P3.3 Player, Mac run 2 (Nov 12, after the pre-roll fix P3-D7)
| File | Workers | Dropped | Late (> 16.7 ms) | Played vs clip | Mean wait | First 100 / last 100 frames |
|---|---|---|---|---|---|---|
| video2 full (2,5), 383 f | 6 | 5 (1.3%) | 6 (1.6%) | 12.77 s vs 12.77 s (+0.04%) | 0.6 ms | 2 drops, 4 late / 3 drops, 2 late |
| video2 full (2,5), 383 f | 4 | 22 (5.7%) | 27 (7.0%) | 12.77 s vs 12.77 s (+0.02%) | 0.4 ms | 12 drops, 15 late / 0 drops, 0 late (30.0 fps) |
| SAL (2,5), 60 f | 6 | 2 (3.3%) | 27 (45.0%) | 2.01 s vs 2.00 s (+0.41%) | 7.2 ms | same 60 frames |
| SAL (2,5), 60 f | 4 | 0 | 6 (10.0%) | 2.01 s vs 2.00 s (+0.35%) | 4.6 ms | same 60 frames |
| SAL (0,0), 60 f | 6 | 0 | 18 (30.0%) | 2.01 s vs 2.00 s (+0.27%) | 3.5 ms | same 60 frames |
| SAL (0,0), 60 f | 4 | 0 | 5 (8.3%) | 2.01 s vs 2.00 s (+0.60%) | 3.3 ms | same 60 frames |

What the numbers say
- Pre-roll fixed the duration: every clip now plays in its true length within 0.6% (H3 target 1%), at both 480p and 1080p.
- 1080p: 4 workers beat 6 in all four SAL runs across runs 1 and 2 (late 8 to 12% vs 27 to 45%), with zero drops. The display scaler and paint share the 8 cores (4 of them efficiency cores) with the decode workers, so fewer workers leaves room to paint on time. Default changed to 4 (P3-D8).
- 480p: video2 decodes in under 1 ms of waiting either way; its drops and late frames sit in the first 100 frames (warm-up), and the last 100 frames ran clean at 30 fps with 4 workers. Worker count does not matter at this size.
- Limitation of these runs: the SAL files are only the first 60 frames, so all of SAL is warm-up. Steady-state 1080p late % (H3 target under 2%) needs a longer file: SAL first 200 frames at (2,5), generated on the Mac (run 3).

### P3.3 Player, Mac run 3 (Nov 12): SAL first 200 frames (2,5), 361.7 MB
| Workers | Order | Dropped | Late | Played vs clip | Mean wait | Frames 0..99 | Frames 100..199 |
|---|---|---|---|---|---|---|---|
| 4 | 1st, right after encoding the 362 MB file | 94 (47.0%) | 37 (18.5%) | 7.28 s vs 6.67 s (+9.2%) | 38.1 ms | 100 shown, 31 late, 29.8 fps | 6 shown, 94 dropped, 1.6 fps |
| 6 | 2nd | 0 | 0 | 6.67 s vs 6.67 s (+0.08%) | 1.5 ms | 100 shown, 30.0 fps | 100 shown, 30.0 fps |

Reading: the 4-worker run collapsed in its second half (a few very long waits: about 4 s of waiting over 106 paints, almost all in frames 100..199), while 6 workers on the same frames were perfect. The bench says 4 workers decode this file at about 60 fps, so this is a stall (disk, memory clean-up or CPU slowdown), not decode speed. Run order matches the earlier pattern: in every SAL pair so far, the FIRST run was the worse one, whatever its worker count. Runs 1 to 3 therefore do not compare 4 and 6 fairly. Run 4 (`out/p3/run4.sh`) cools down first, alternates 4,6,6,4 with pauses, and logs per-frame waits and Java memory clean-up (GC) pauses. The default stays 4 only provisionally (P3-D8) until run 4.

### P3.3 Player, Mac run 4 (Nov 12): fair 4 vs 6 workers, SAL first 200 frames (2,5)
`out/p3/run4.sh`: 60 s cool-down, order 4,6,6,4, 20 s pauses, per-frame traces `out/p3/r4_trace_*.csv`, GC logs `out/p3/r4_gc_*.log`. Plus one extra 4-worker run straight after re-encoding the file (the run 3 situation).

| Run | Workers | Dropped | Late (> 16.7 ms) | Played vs clip | Late in frames 100..199 | Worst lateness | Longest gap between paints | Wait p99 / max |
|---|---|---|---|---|---|---|---|---|
| extra, right after encode | 4 | 0 | 9 (4.5%) | +0.08% | 0 | | | |
| 4a | 4 | 0 | 11 (5.5%) | +0.07% | 1 | 46.2 ms (frame 7) | 74.2 ms | 19.4 / 27.3 ms |
| 6a | 6 | 0 | 17 (8.5%) | +0.13% | 8 | 41.1 ms | 51.0 ms | 18.9 / 18.9 ms |
| 6b | 6 | 0 | 6 (3.0%) | +0.07% | 2 | 41.4 ms | 51.4 ms | 7.5 / 17.4 ms |
| 4b | 4 | 0 | 1 (0.5%) | +0.07% | 0 | 37.6 ms (frame 7) | 65.7 ms | 4.3 / 4.5 ms |

Bench on the same 200 frames: serial 51.0 ms/frame; 4 workers 17.5 ms = 57.2 fps (1.91x real time); 6 workers 14.7 ms = 67.9 fps (2.26x). Thermal: no warning recorded.

What the numbers say
- Drop counting checked against the traces: every frame is either painted or flagged dropped, none is neither or both. Zero drops in all five runs is real. A frame is dropped only if it is already a full period behind when the player picks it; the worst frame here finished 46 ms after its deadline but was about 13 ms late when chosen, so it counts as late, not dropped.
- 4 vs 6, fair order: 4 workers averaged 3.5% late (0.5 to 5.5%), 6 workers 5.75% (3.0 to 8.5%); in the steady part (frames 100..199) 4 workers had 0 to 1 late frames, 6 workers 2 to 8. Both 0 drops, both on time to 0.13%. 4 workers stay the default (P3-D8 confirmed); 6 decode faster in the bench but the extra speed is not needed.
- Late frames cluster in the first 2 seconds (frames 7 to 62): Java compiles the hot code while it runs (JIT warm-up). Steady-state 1080p meets the H3 target (under 2% late, zero drops); counted over the whole clip including warm-up it is 0.5 to 5.5%.
- Run 3's collapse did not come back, even straight after re-encoding the 362 MB file. GC pauses are small (largest 25 ms, total 33 to 82 ms per run), so memory clean-up is not the cause. Most likely a one-off disk stall on a 94% full disk; freeing disk (06 item D9) also lowers the demo risk.

### P1.3 hierarchical vs full search [A/P1] (sandbox, 4 cores, not authoritative for speed; Mac run queued)
`VizSeg <clip> --range R --me-check`: 5 evenly spaced frame pairs per clip. Agreement = both vector components within 1 px of full search, counted on textured blocks only (block variance >= 100). "Worse" = the hierarchical match error is more than 10% above full search's (a real miss, not an equally good alternative). Final version: 3 levels, +/-8 coarse (about +/-32 px), 3 coarse starts refined separately, +/-2 refine, extra candidates, +/-1 polish, lambda 4.

| Clip | Full R | Textured blocks | Agree, lambda 4 | Agree, lambda 0 | Worse by >10% | Full ms/frame | Hier ms/frame |
|---|---|---|---|---|---|---|---|
| video2 | 32 | 5,348 | 95.8% | 95.9% | 1.6% | 224 | 37 |
| video1 | 16 | 2,648 | 91.7% | 91.1% | 4.0% | 64 | 37 |
| Stairs | 16 | 26,943 | 91.8% | 91.6% | 3.0% | 340 | 193 |
| SAL | 16 | 28,240 | 89.8% | 89.5% | 4.0% | 383 | 193 |
| SAL (check) | 32 | 28,240 | 87.9% | 88.8% | 6.6% | 1,334 | 195 |

How the design got there (SAL agreement): single coarse winner 87.3%, plus a +/-1 polish 89.0%, plus 3 coarse starts 89.8%. Each step cost: hier 108 to 109 to 193 ms per 1080p frame (sandbox).
Reading: 3 of 4 clips meet the 90% target; SAL is 0.2 points short. Most SAL disagreements are equally good matches on the brick wall's repeating pattern: with a wider +/-32 full search, full search finds even lower-error matches further away (6.6% "worse"), which are period-shifted aliases, not true motion. On periodic texture the lowest match error is not the true vector, which is why lambda exists and why segmentation also requires neighbours to agree (P1.6).

### P1.4 reliability [A/P1] (sandbox, same 5 frame pairs as P1.3, hierarchical vectors)
Reliable = block Y variance >= 100 and best-match MAD <= 12 (`Segmenter.SegConfig.DEFAULT`).

| Clip | Reliable blocks |
|---|---|
| video1 | 31.4% |
| video2 | 59.4% |
| Stairs | 64.3% |
| SAL | 68.5% |

Reading: video1 is a night scene with large dark, flat areas, so most blocks carry no usable motion evidence (doc 05 predicted 37 to 52% textured before the match test). These blocks are not labelled from their own vector; P1.7 gives them their neighbours' label. Thresholds are revisited in P1.9 tuning.

P1.3 acceptance note [A/P1, Nov 13]: PM accepted SAL at 89.8% agreement (target 90%) because the disagreements are equal-quality brick aliases; the real-miss figure ("worse by >10%") is 4.0% on SAL and 1.6 to 4.0% across clips. Both numbers are reported together from here on. Decision log P1-L10.

### P1.5 camera motion [A/P1] (sandbox, 4 cores, every frame pair of every clip; fit times not authoritative)
`VizSeg <clip> --cam-stats`: hierarchical vectors, reliable blocks only (P1.4), RANSAC homography (500 iterations, seed 576, inlier distance 2.5 px, refit twice on inliers), fallback to affine then translation below 30% inliers or when the fit throws a frame corner more than 20% of the frame side. Per-frame CSV in `out/viz/<clip>_cam.csv`.

| Clip | Pairs | Model mix | Mean inlier ratio (min) | Mean inlier error | Reliable blocks | Doc 05 prototype (homography, 3 px, textured) |
|---|---|---|---|---|---|---|
| video1 | 517 | homography 100% | 0.770 (0.357) | 1.07 px | 32.3% | 0.77 to 0.81 |
| video2 | 382 | homography 381, translation 1 | 0.737 (0.177) | 1.07 px | 58.7% | 0.58 to 0.63 |
| Stairs | 345 | homography 100% | 0.902 (0.675) | 0.72 px | 63.6% | 0.89 to 0.95 |
| SAL | 436 | homography 100% | 0.853 (0.613) | 0.61 px | 66.9% | 0.75 to 0.84 |

Reading: on the three 30 fps walking/pan clips the homography explains 77 to 90% of trustworthy blocks within 2.5 px, matching or beating the prototype. video2 is higher than the prototype because unreliable blocks no longer vote. The one fallback is video2 frame 30 (18% inliers, translation); its other low frames (60, 150, 180 at about 38%) are where the tracking shot shakes. Those frames are the reason for the 2-of-3 temporal vote in P1.8. Fit cost 1.5 to 10 ms per frame, small next to motion search.

### P1.5 naive baseline overlays [A/P1] (saved BEFORE any segmentation tuning)
`VizSeg <clip> --naive --sheet docs/img/naive_<clip>.jpg` at 20/50/80% of each clip. Naive = one frame, P1.5 camera model, a textured block (variance >= 100) is foreground if its residual is over 3 px, 4-connected groups of at least 4 blocks kept. No match gate, no direction consistency, no cleaning, no temporal vote.

| Clip | Frames | Foreground % | What it gets right | What it gets wrong |
|---|---|---|---|---|
| video1 | 103, 259, 414 | 2.4, 7.6, 3.8 | cyclist, one pedestrian | most dark pedestrians missed; crosswalk stripe edges and building edges false |
| video2 | 76, 191, 306 | 23.5, 24.8, 6.3 | both joggers partly | ground, fence and trees false (parallax and fast shake) |
| Stairs | 69, 173, 276 | 2.4, 5.5, 8.6 | walker outline | flat jacket interior missed; railings and bricks scattered false |
| SAL | 87, 218, 349 | 12.1, 11.1, 3.8 | walker legs and body | large false patches on the brick wall (period aliases), paving tiles |

Images: `docs/img/naive_video1.jpg`, `naive_video2.jpg`, `naive_Stairs.jpg`, `naive_SAL.jpg` (half size, three frames each). P1.6 to P1.9 are judged against these.

### P3.4 / P3.4b Mac checks, Nov 13 (D14)
- Error path (P3.6): `./mydecoder data/SAL.mp4` prints `error: not a .cmp file (magic 0x00000014, expected LYRC)` and exits 1. PASS.
- View (P3.4, P3.5): side by side, 1:1 zoom (same region in both halves), drag, overlay and status tags work (Dhruvin's screenshot). The grey area in the screenshot came from maximizing the window. Step back 35 frames: not yet tried.
- Playback WITH the original mp4 (first version, original read on the player thread): SAL_2_5 60 frames dropped 27 (45%) and 8 (13%) in two runs, vs 0 drops without the original. Fixed in P3.4c (background reader); re-measure pending.
- run4.sh repeated twice for P3.4b; neither run counts as a clean measurement:
  - Run A (about 14:15): bench 4 workers 45.1 fps vs 57.2 on Nov 12 (same file), single core 54.4 vs 51.0 ms: the Mac was loaded (another build window was working in the device VM, which runs on the same Mac). 6b was interrupted by a key.
  - Run B (about 14:22): bench 52.2 fps (4 workers), 64.1 (6). 4a collapsed (100 dropped, mean wait 23.5 ms). Its trace shows two whole-process freezes: a 1.36 s wait at frame 86 and a 1.84 s gap between paints at frame 147 with no decoder wait; GC max 7.7 ms. So the process itself was stopped (most likely system memory swapping on a 94% full disk), not slow decoding. 6b, the cleanest run: 0 dropped, 5 late (2.5%), +0.07%. 4b quit early with q.
- P3.4b verdict: not decided yet. Next timing runs only on a quiet Mac (00/06 timing rule) after freeing disk (D9).
- P3.4c check (Nov 13, about 14:38, back to back, traces out/p3/t_*.csv): SAL_2_5 60 frames WITHOUT the original: 5 dropped (frames 15 to 23), 9 late, +0.23%, max wait 51.5 ms (a short hiccup in the first run after the build). WITH the original (read ahead on its own thread): 0 dropped, 1 late (1.7%), 30.0 fps, +0.14%, max wait 23.7 ms. The background reader removes the drops seen with the first version (13 to 45%). An earlier P3.4c run at 14:32 (65% dropped, mean decoder wait 55.4 ms) came straight after a build on a loaded Mac and is not counted.

### P1.6 grouping [A/P1] (sandbox; same frames as the naive sheets: 20/50/80% of each clip)
`VizSeg <clip> --seg --sheet ...`. Candidates = eligible blocks (good match, texture >= 20) not explained by the camera, residual > 2.5 px; 4-connected growth where a neighbour joins only if its residual is within 2 px, or both are >= 4 px long and within 30 degrees; regions kept if >= max(4, 0.2% of blocks) and robust spread (median distance from the median residual) <= 3 px.

Foreground % per frame (three frames per clip), as each change was added:

| Step | video1 | video2 | Stairs | SAL |
|---|---|---|---|---|
| Naive (P1.5) | 2.4 / 7.6 / 3.8 | 23.5 / 24.8 / 6.3 | 2.4 / 5.5 / 8.6 | 12.1 / 11.1 / 3.8 |
| A: consistency grouping, reliable blocks only, RMS spread <= 3 | 1.5 / 4.5 / 1.6 | 7.6 / 7.2 / 2.7 | 0.0 / 0.2 / 0.4 | 1.3 / 1.5 / 0.6 |
| B: + looser candidate gate (texture >= 20) | 2.9 / 5.8 / 2.2 | 8.4 / 9.7 / 2.6 | 0.2 / 0.2 / 0.0 | 1.6 / 1.0 / 1.2 |
| C: + robust spread (median), cap 3 px | 4.2 / 6.2 / 3.1 | 14.5 / 11.8 / 4.3 | 0.2 / 3.6 / 2.1 | 2.7 / 5.6 / 1.5 |
| D: + camera-explained test (final P1.6) | 3.1 / 3.5 / 1.9 | 12.5 / 11.3 / 3.4 | 0.0 / 3.0 / 1.9 | 1.9 / 3.7 / 1.2 |

Region-level checks (diagnostic on the walker's block rectangle): Stairs f173, walker blocks kept: A 0, C 222 of 559. SAL f218, walker blocks kept with robust spread cap 2 px: 0; cap 3 px: 88 (torso residual about 9 px, legs 16 to 25 px, real articulated motion).

Reading (images `docs/img/p16_*.jpg`, checked by eye): step A removed most false foreground but also the walkers, because their flat clothing failed the texture test (Stairs f173: 225 of 559 walker blocks textured, 539 well matched). B and C bring the walkers back. D removes 15 to 44% of what remains on SAL and video1 (brick and stripe aliases that match the camera's vector as well as their own). Result vs naive: SAL brick-wall false foreground down from 11 to 12% to 1 to 4%, walker found in all three SAL frames; video2 halved; Stairs walker found in f173 and f276. Remaining: Stairs f69 walker missed (small and far, see P1.6 note below), some brick patches on SAL, video2 trees and fence (parallax).
