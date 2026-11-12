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
