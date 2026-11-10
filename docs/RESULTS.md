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
| Clip | ME ms/frame full | ME ms/frame hier | Agreement within 1 px | Camera model mix | Mean inlier ratio | Mean foreground % | Regions/frame |
|---|---|---|---|---|---|---|---|
