# MAP: open this when you want to...

## Run it
- `myencoder`, `mydecoder` (+ `.bat`): run the codec with the brief's exact commands.
- `demo/demo.sh`: run the live demo from a numbered menu (0 check and pre-encode, 1 to 9 demo steps).
- `demo/DEMO.md`: follow the 2-minute demo, key by key, with what to say.
- `demo/ASKS.md`: answer "can you show me X" during questions.
- `demo/TESTS.md`: run one important test by name.
- `demo/DEBUG.md`: fix something that breaks during the demo.
- `data/`: the 4 mp4 clips and their .cmp files (git ignores it).
- `out/`: scratch output such as segmentation pictures (git ignores it, safe to empty).

## Read the code (frozen: tag freeze-code, no edits)
- `src/main/java/codec/encoder/`: see how foreground is found (MotionEstimator, GlobalMotion, Segmenter) and how `myencoder` starts (EncoderMain).
- `src/main/java/codec/common/`: see the shared pieces: DCT, quantization (Quant), the .cmp file (CmpFormat), frame encoder, run-length (mode 1), mode 2 codec, video reading, display, clock.
- `src/main/java/codec/decoder/`: see how `mydecoder` decodes in parallel and plays (DecodeCore, DecodePipeline, GopPipeline for mode 2, FrameCache, Player).
- `src/main/java/codec/tools/`: use helper tools: CmpDump (file layout), VizSeg (segmentation pictures), McEncode (mode 2 files), Psnr, ProbeVideo, SpeedGate, CodecHarness.
- `src/test/java/codec/`: read the 234 JUnit tests, one folder per part.
- `build.gradle.kts`, `gradlew`: build the jar and run tests (`./gradlew build`).
- `build/libs/layer-codec.jar`: the built program the launchers run.

## Prove it
- `tools/final_check.sh`: rerun the full proof (about 30 min, quiet Mac, 6+ GB disk).
- `docs/SPEC_CHECKLIST.md`: see every requirement A1 to F2 and its proof.
- `docs/RESULTS.md`: look up a measured number (60 KB: grep `^## ` and read one section).
- `docs/img/`: show a picture (swap crops, segmentation, side by side, sizes).

## Explain it
- `docs/final_report_draft.md`: read the whole project as a report.
- `docs/DECISIONS.md`: see the decisions where the brief is silent.
- `docs/decision-log/`: dig into why a choice was made, by phase (126 KB: read one part).
- `docs/WORKLOG.md`: see what happened when (47 KB: grep, do not read whole).
- `docs/p1_report.md`, `docs/p3_report_draft.md`: read the segmentation and decoder reports.
- `presentation/`: study and prepare the talk (LEARN.md, study_guide.md, demo scripts, notes).
- `lab/`: the lab work L0 to L5 (separate `lab` branch, not on main, git excludes it here).
