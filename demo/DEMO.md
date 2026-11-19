# Demo: the 2-minute path

Before the talk: plug in, close other apps, then in Terminal:
`cd ~/Documents/layer-codec && conda activate base && caffeinate -dims &`
`bash demo/demo.sh` (add `DISK_OK=1` in front only if disk is under 15 GB and you accept it).
Run item 0 once (pre-encodes 3 files, about 1 minute) and item 1 (warm-up). Then:

| Step | Key or command | Say |
|---|---|---|
| 1 | menu 2 | "This is the brief's exact command: myencoder, the clip, n1 = 2, n2 = 5. It finds moving blocks from motion only, then compresses." |
| 2 | (wait about 20 s) read the `encoded` line | "383 frames, 85.4 MB, under the 10 minute limit by a wide margin." |
| 3 | menu 4 | "The decoder needs only the .cmp file. I also give it the original so we can compare." |
| 4 | `space` | "It plays at the original 30 frames per second." |
| 5 | `space`, then `right` twice, `left` once | "Pause, step forward, step back." |
| 6 | `s` | "Left is decoded, right is the original." |
| 7 | `d` | "Tinted blocks are what the encoder called foreground. The type byte in the file tells the decoder this." |
| 8 | `z`, drag over the person | "At 1:1 the person (n1 = 2) is clean, the background (n2 = 5) is blockier. That is the whole idea: spend bits where people look." |
| 9 | `q`, read the `play` line | "0 dropped frames, timing within 0.1%." |
| 10 | menu 6 | "Extra credit: zigzag plus run-length is 60.9 MB, motion-compensated is 54.5 MB, against 85.4 MB." |

If time is left: menu 5 (swap on SAL), menu 8 (file layout), menu 9 (naive vs final segmentation).

Numbers: all from the demo run on the Mac, Nov 19 2026 (video2 at (2,5)).
