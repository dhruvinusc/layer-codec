#!/usr/bin/env bash
# Final full check before the demo (Window C, freeze Nov 15). Quiet Mac, charger in, conda base active.
# Build + all tests, encode all 4 clips in all 3 modes, PSNR, decode bench, play every file untouched
# (press q only when a clip has ended), then the n1/n2 swap check on SAL with side by side.
# About 30 minutes. Writes into out/final/ only (data/ is not touched). Log: out/final/final_check.txt
set -u
cd "$(dirname "$0")/.."
O=out/final
mkdir -p $O/base $O/rle $O/mc $O/swap
LOG=$O/final_check.txt
: > "$LOG"
say() { echo "$@" | tee -a "$LOG"; }
run() { "$@" 2>&1 | grep -v 'Picked up JAVA_TOOL_OPTIONS' | tee -a "$LOG"; }
JAR=build/libs/layer-codec.jar
CLIPS="video1 video2 Stairs SAL"
say "=== final_check.sh $(date)  commit $(git rev-parse --short HEAD) ==="
say "power: $(pmset -g batt | head -1)"
run df -h .
command -v ffmpeg >/dev/null || { say "stopped: ffmpeg not on PATH (conda activate base)"; exit 1; }
say "--- 1. build + all tests ---"
run ./gradlew build || { say "stopped: build or tests failed"; exit 1; }
grep -ho 'tests="[0-9]*"' build/test-results/test/*.xml | tr -dc '0-9\n' | paste -sd+ - | bc | sed 's/^/tests run: /' | tee -a "$LOG"
say "--- 2. encode all 4 clips in 3 modes at (2,5) ---"
for c in $CLIPS; do
  for m in base rle; do cp data/$c.mp4 $O/$m/$c.mp4; done
  s=$(date +%s); run ./myencoder $O/base/$c.mp4 2 5;            say "wall $c baseline: $(( $(date +%s) - s )) s"
  s=$(date +%s); run ./myencoder $O/rle/$c.mp4 2 5 --mode rle;  say "wall $c rle: $(( $(date +%s) - s )) s"
  s=$(date +%s); run java -Xmx4g -cp $JAR codec.tools.McEncode data/$c.mp4 2 5 $O/mc/$c.cmp; say "wall $c mc: $(( $(date +%s) - s )) s"
  run java -Xmx4g -cp $JAR codec.tools.Psnr data/$c.mp4 $O/base/$c.cmp
  rm -f $O/base/$c.mp4 $O/rle/$c.mp4 2>/dev/null
done
run ls -l $O/base $O/rle $O/mc
say "--- 3. decode bench, 4 workers ---"
for c in $CLIPS; do for m in base rle mc; do run ./mydecoder $O/$m/$c.cmp --bench --workers 4; sleep 5; done; done
say "--- 4. play every file UNTOUCHED (q only at the end) ---"
for c in $CLIPS; do for m in base rle mc; do say "=== play $c $m ==="; run ./mydecoder $O/$m/$c.cmp; sleep 5; done; done
say "--- 5. swap check: SAL at (5,2), then side by side (press space, s; then q) ---"
cp data/SAL.mp4 $O/swap/SAL.mp4
run ./myencoder $O/swap/SAL.mp4 5 2
run java -Xmx4g -cp $JAR codec.tools.Psnr data/SAL.mp4 $O/swap/SAL.cmp
run ./mydecoder $O/swap/SAL.cmp data/SAL.mp4
say "--- 6. after ---"
run df -h .
run pmset -g therm
say "done. Paste out/final/final_check.txt to Window C. Delete out/final/ afterwards to free about 3 GB."
