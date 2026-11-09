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

## P0 speed gate
| Test | Result | Target |
|---|---|---|
| Display 300 frames 1080p at 30 fps, late frames | | < 2% |
| 16x16 SAD | | ns per SAD |
| 8x8 IDCT, one 1080p frame x 3 channels, 1 thread / all cores | | < 25 ms all cores |
| Inflate 12.5 MB | | |

## P1 segmentation
| Clip | ME ms/frame full | ME ms/frame hier | Agreement within 1 px | Camera model mix | Mean inlier ratio | Mean foreground % | Regions/frame |
|---|---|---|---|---|---|---|---|
