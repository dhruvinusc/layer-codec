# Results

Every number here comes from a Release-equivalent run on Dhruvin's Mac (launcher, after JIT warm-up) unless marked "sandbox".

## Environment
| Item | Value |
|---|---|
| Machine | MacBook Air, Apple Silicon, macOS 14.2.1 |
| JDK | Temurin 21 |
| FFmpeg | 7.1 |

## P0 probe (VideoInfo per clip)
| Clip | Upright size | Padded | Frames | fps | Rotation | Thumbnail |
|---|---|---|---|---|---|---|

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
