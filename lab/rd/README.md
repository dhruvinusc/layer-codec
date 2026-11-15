# Lab L3: Bits and Budgets

The professor's framing: the codec "distributes compression bits unevenly between foreground and background for streaming". These tools show it with numbers. They call the frozen codec (commit 88fc181) read-only and change nothing in `src/`, the launchers, or any `.cmp` the graded path writes.

## Run
Follows `lab/README.md` (binding). Code in `lab/src/rd/` (package `lab.rd`), compiled only by `lab/run.sh` against the frozen jar. From the repo root, after `./gradlew -q jar`: `./lab/run.sh rd lab.rd.<Tool> args`. Outputs go to `lab/out/rd/` (git-ignored). Copy chosen images into `docs/img/` only through PM.

Whole Mac run: `bash lab/rd/mac_rd.sh` (5 to 8 minutes, no `.cmp` written, log `lab/out/rd/mac_rd.txt`).

| Tool | What it shows |
|---|---|
| `lab.rd.DctExplain <clip> --at f` | One foreground and one background 8x8 block: pixels, DCT heat grid, q at n=2 and n=5 with zero counts, rebuilt blocks with block PSNR. One PNG. |
| `lab.rd.RdSweep <clip> [--frames 120] [--n1 0:7] [--n2 0:7]` | Every (n1, n2) pair in all 3 modes: exact file bytes, Mbps, PSNR all/fg/bg. CSV. |
| `python3 lab/rd/plot_rd.py [csv]` | RD charts (fixed n1, even vs uneven) and the 4-clip size bars. |
| `lab.rd.RateControl <clip> n1 --target-mbps X [--mode 0\|1\|2\|all] [--full]` | Keeps n1, binary-searches the smallest n2 in n1..10 that fits the budget, on 3 x 30-frame segments at 20/50/80% of the clip. |

## How it stays honest
- `RdCore` codes a segment in memory with the codec's own classes (`FrameEncoder`, `RunLength`, `McCodec`, `DecodeCore`, `Psnr`, `Zio`) and counts the exact bytes the real writer would write (48-byte header, 8-byte prefix per frame, zlib payload, 8-byte footer entry per frame).
- Checked byte for byte: a 20-frame segment equals `McEncode --frames 20` (3,102,696 bytes). Whole video2 at (2,5) equals RESULTS "P4 final" in all 3 modes: 86.70 / 61.59 / 55.07 MB, PSNR fg 47.78 / bg 33.99 (modes 0 and 1) and 47.55 / 33.76 (mode 2).
- Labels: `Segmenter.Run` with the default config, started fresh at each segment's first frame (as McEncode does at frame 0). A mid-clip segment can differ slightly from whole-clip labels at its first frames; this changes sizes by well under the effects shown.
- Bitrate = bytes x 8 / seconds of video, 1 Mbps = 10^6 bit/s. Segment estimates matched whole-clip rates within 2.5% on SAL (2,5).
- Charts are static PNG (slides, report), drawn with matplotlib using the dataviz reference palette (slots 1 to 3, validated: adjacent CVD dE 9.2, normal-vision 27.6; aqua is under 3:1 contrast, so every series is also direct-labelled with its own marker). Python is only for charts; nothing graded uses it.
