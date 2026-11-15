# lab/

Created by L0 Lab Lead on Nov 15 on top of frozen main 88fc181 (results 3edab21).

Note: this file was RECONSTRUCTED by L1 on Nov 15. L1 overwrote L0's original README.md and run.sh by
mistake a few minutes after L0 created them (lab/ is untracked, so git has no copy). run.sh is restored
word for word from L0's chat. The nine rules below are L0's rules word for word from L0's brief; the
layout and usage sections are rewritten from L0's report. L0: please check and correct.

Framing from the professor: distributing compression bits unevenly between foreground and background
for better compression efficiency for streaming. Lab freeze: Nov 24 night.

## Rules
1. Each tab writes only inside its own lab/<tab>/ and lab/out/<tab>/, plus append-only tagged entries in LAB_LOG.md and PROMOTE.md.
2. Read anything in the repo; edit nothing outside lab/. Use frozen classes through the jar; if a class must change, copy it into lab.<tab> under a new name and say so in LAB_LOG.
3. No git add, commit, stash or checkout. lab/ stays untracked. Never git add -A.
4. Packages lab.<tab>.*, never codec.*. Same code standards as 00 section 8.
5. Mac is the only timing authority. Keep outputs short clips; list big files for Dhruvin to delete. Disk ~18 GB free.
6. Motion only, no content detectors. Brief CLI and .cmp v1 untouched; lab-only formats use a new mode value and live in lab.
7. Teaching rule: idea in 5 lines, Dhruvin predicts, build, Dhruvin explains back.
8. No em-dashes or double hyphens in any text.
9. End each session: LAB TICKS: <items>; OUT: <files>; PROMOTE: <yes/no + cost>; BLOCKED: <none or reason>.

Lab-only .cmp mode values: 3 to 7 (frozen code uses 0, 1, 2). Claim one in LAB_LOG.md before using it.

## Layout
```
lab/
  README.md  build.sh  run.sh  .gitignore (build/ out/)  LAB_LOG.md  PROMOTE.md
  player/src/lab/player/   L1 Player Polish
  viz/src/lab/viz/         L2 Motion Explainer
  rd/src/lab/rd/           L3 Bits and Budgets
  ec/src/lab/ec/           L4 Extra Credit Lab
  story/                   L5 Story
  out/<tab>/               generated media, deletable
  build/                   ignored: <tab>.jar and classes
```

## Build and run (from the repo root)
```
./lab/build.sh <tab>                    compiles lab/<tab>/src against build/libs/layer-codec.jar into lab/build/<tab>.jar
./lab/run.sh <tab> <MainClass> [args]   runs it from the repo root; a name without a dot means lab.<tab>.<Name>
```
Both find JDK 21 the same way the frozen launchers do (LAYER_CODEC_JAVA, java_home -v 21, JAVA_HOME).
Smoke test: `./lab/build.sh player && ./lab/run.sh player Hello`.
