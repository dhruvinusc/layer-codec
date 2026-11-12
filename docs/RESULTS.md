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
