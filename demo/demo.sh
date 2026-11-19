#!/usr/bin/env bash
# demo/demo.sh  Numbered demo menu. Run from anywhere: bash demo/demo.sh
# Flags used here were read from the source usage text on Nov 19 (EncoderMain, DecoderMain, McEncode, CmpDump, VizSeg).
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

JAR=build/libs/layer-codec.jar
J="$(/usr/libexec/java_home -v 21 2>/dev/null)/bin/java"
[[ -x "$J" ]] || J=java
tool() { "$J" -Xmx4g -cp "$JAR" "codec.tools.$1" "${@:2}"; }

V=data/video2.mp4
BASE=data/video2.cmp
RLE=data/video2_rle.cmp
MC=data/video2_mc.cmp
N1=2; N2=5

free_gb() { df -k . | awk 'NR==2{printf "%d", $4/1048576}'; }
mb() { [[ -f "$1" ]] && awk -v b="$(stat -f%z "$1" 2>/dev/null || stat -c%s "$1")" 'BEGIN{printf "%.1f MB", b/1e6}' || echo "missing"; }
pause() { read -r -p "Press Enter for the menu..." _; }

item0() {
  echo "== 0. Check and pre-encode =="
  ok=1
  command -v ffmpeg >/dev/null && echo "ffmpeg: ok" || { echo "ffmpeg: MISSING (run: conda activate base)"; ok=0; }
  "$J" -version 2>&1 | head -1
  [[ -f "$JAR" ]] && echo "jar: ok" || { echo "jar: MISSING (run: ./gradlew build)"; ok=0; }
  g=$(free_gb); echo "disk free: ${g} GB"
  if (( g < 15 )); then
    if [[ ${DISK_OK:-0} == 1 ]]; then echo "disk: under 15 GB, going on anyway (DISK_OK=1)"
    else echo "disk: under 15 GB, free space first (or run: DISK_OK=1 bash demo/demo.sh)"; ok=0; fi
  fi
  for f in data/video2.mp4 data/SAL.mp4 data/SAL.cmp; do [[ -f $f ]] && echo "$f: ok" || { echo "$f: MISSING"; ok=0; }; done
  (( ok )) || { echo "STOP: fix the lines above."; return 1; }

  echo "-- baseline -> $BASE"
  time ./myencoder "$V" $N1 $N2 || return 1
  # myencoder --mode rle always writes <input>.cmp, so feed it a linked copy named video2_rle.mp4
  echo "-- mode 1 (rle) -> $RLE"
  ln -sf video2.mp4 data/video2_rle.mp4
  time ./myencoder data/video2_rle.mp4 $N1 $N2 --mode rle || return 1
  echo "-- mode 2 (motion compensated) -> $MC"
  time tool McEncode "$V" $N1 $N2 "$MC" || return 1
  echo "sizes: baseline $(mb $BASE), mode 1 $(mb $RLE), mode 2 $(mb $MC)"
}

item1() { echo "== 1. Warm-up (no window) =="; ./mydecoder "$BASE" --bench; }
item2() { echo "== 2. Encode video2 live at ($N1,$N2) =="; time ./myencoder "$V" $N1 $N2; }
item3() { echo "== 3. Play. Keys: space pause, right step, left step back, q quit =="; ./mydecoder "$BASE"; }
item4() { echo "== 4. Side by side. Keys: s split, z 1:1 zoom (drag), d foreground overlay =="; ./mydecoder "$BASE" "$V"; }

item5() {
  echo "== 5. Swap n1/n2 on SAL =="
  echo "SAL.cmp is the (2,5) file: sharp subject, rough background."
  [[ -f docs/img/p3r_SAL_swap_crop_x3.png ]] && open docs/img/p3r_SAL_swap_crop_x3.png
  ./mydecoder data/SAL.cmp data/SAL.mp4
  if [[ -f data/SAL_52.cmp ]]; then
    echo "Now (5,2): rough subject, sharp background."
    ./mydecoder data/SAL_52.cmp data/SAL.mp4
  else
    echo "No data/SAL_52.cmp yet. Make it once (about 130 s, about 1.1 GB):"
    echo "  ln -sf SAL.mp4 data/SAL_52.mp4 && ./myencoder data/SAL_52.mp4 5 2"
  fi
}

item6() {
  echo "== 6. Extra credit sizes, video2 at ($N1,$N2) =="
  printf "  baseline  %s\n  mode 1    %s\n  mode 2    %s\n" "$(mb $BASE)" "$(mb $RLE)" "$(mb $MC)"
  [[ -f $RLE && -f $MC ]] || { echo "Mode 1 or mode 2 file missing: run item 0 first."; return 1; }
  read -r -p "Play mode 1 then mode 2? [y/N] " a
  [[ $a == y ]] && { ./mydecoder "$RLE" "$V"; ./mydecoder "$MC" "$V"; }
}

item7() {
  echo "== 7. Speed (frames per second, 4 workers, no window) =="
  for f in "$BASE" "$RLE" "$MC"; do echo "-- $f"; ./mydecoder "$f" --bench --workers 4; done
}

item8() { echo "== 8. File layout: header + first blocks of frame 0 =="; tool CmpDump "$BASE" --frame 0 --blocks 3; }

item9() {
  echo "== 9. Segmentation pictures, frame 100: naive vs final =="
  tool VizSeg "$V" --at 100 --naive --out out/viz/naive
  tool VizSeg "$V" --at 100 --seg --out out/viz/final
  open out/viz/naive out/viz/final 2>/dev/null
}

while true; do
  cat <<'M'

 0 check + pre-encode 3 modes   5 swap SAL (2,5) vs (5,2)
 1 warm-up                      6 mode 1 and mode 2 sizes
 2 encode video2 live           7 speed line
 3 play                         8 file layout (CmpDump)
 4 side by side, zoom, overlay  9 segmentation pictures
 q quit
M
  read -r -p "> " c
  case $c in
    [0-9]) "item$c"; pause ;;
    q) exit 0 ;;
  esac
done
