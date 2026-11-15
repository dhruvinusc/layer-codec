#!/usr/bin/env bash
# Lab L3 on the Mac (authoritative). From the repo root, conda base active (ffmpeg + matplotlib), charger in:
#   ./gradlew -q jar && bash lab/rd/mac_rd.sh
# About 5 to 8 minutes. Writes ONLY into lab/out/rd/ (git-ignored). Writes no .cmp files: every encode is
# in memory, so disk use is a few MB of CSV and PNG. Log: lab/out/rd/mac_rd.txt
set -u
cd "$(dirname "$0")/../.."
O=lab/out/rd; mkdir -p $O; LOG=$O/mac_rd.txt; : > "$LOG"
run() { "$@" 2>&1 | grep -v 'Picked up JAVA_TOOL_OPTIONS' | tee -a "$LOG"; }
say() { echo "$@" | tee -a "$LOG"; }
say "=== mac_rd.sh $(date)  commit $(git rev-parse --short HEAD) ==="
command -v ffmpeg >/dev/null || { say "stopped: ffmpeg not on PATH (conda activate base)"; exit 1; }
[[ -f build/libs/layer-codec.jar ]] || { say "stopped: run ./gradlew build first"; exit 1; }
say "--- 0. check: in-memory core == real files (video2 whole clip, (2,5); RESULTS Final Mac check: 85.4 / 60.9 / 54.5 MB) ---"
run ./lab/run.sh rd lab.rd.RdSweep data/video2.mp4 --frames 1000 --n1 2 --n2 5 --out $O/check_video2_full_mac.csv
say "--- 1. DCT picture ---"
run ./lab/run.sh rd lab.rd.DctExplain data/SAL.mp4 --at 150
run ./lab/run.sh rd lab.rd.DctExplain data/SAL.mp4 --at 218
say "--- 2. RD sweep, video2 first 120 frames, 64 pairs x 3 modes ---"
s=$(date +%s); run ./lab/run.sh rd lab.rd.RdSweep data/video2.mp4 --frames 120 --out $O/rd_video2.csv; say "wall sweep: $(( $(date +%s) - s )) s"
say "--- 3. charts (RD + sizes) ---"
run python3 lab/rd/plot_rd.py $O/rd_video2.csv --tag mac
say "--- 4. rate control ladder (n1 = 2 kept) ---"
rm -f $O/ratecontrol.csv
for b in 150 100 60 30; do run ./lab/run.sh rd lab.rd.RateControl data/SAL.mp4 2 --target-mbps $b; done
for b in 40 20 12; do run ./lab/run.sh rd lab.rd.RateControl data/video2.mp4 2 --target-mbps $b; done
run ./lab/run.sh rd lab.rd.RateControl data/SAL.mp4 2 --target-mbps 100 --mode 2 --full
say "=== done $(date) ==="
