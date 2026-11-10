# Worklog

Running handoff between build windows. Newest entry at the bottom.

## 2026-11-09, Window A (P0)
- P0.1 done. Mac: macOS 14.2.1 arm64, JDK 20 default plus Temurin 21 installed via brew cask, FFmpeg 7.1 (conda-forge, on PATH while conda base is active), git 2.46.0, Homebrew 6.0.6. Build sandbox (Linux VM): Temurin 21.0.9 and Gradle 9.9.0 pulled from GitHub; no Maven Central access.
- P0.2 done in sandbox: Gradle skeleton, launchers, stubs with argument validation. `gradle jar` green with -Xlint:all -Werror; launcher checks pass. Commit 0ceebe9.
- P0.3 done: SPEC_CHECKLIST, DECISIONS, WORKLOG, RESULTS, presentation/notes.md headings.
- P0.2 proven on the Mac: `./gradlew build` 9/9 tests green, launcher printed validated args with JDK 21. `vendorJunit` done; sandbox runs the same tests offline.
- Prototype files (naive_overlay.jpg, probe_motion.py) not available; see DECISIONS P0-D5.
- P0.4 Types.java written as proposed (planar int frames, VideoInfo, MotionField, Labels). AWAITING Dhruvin's yes; P1 must not build on it before that.
- P0.5 VideoIO + ProbeVideo done. 15/15 tests in sandbox, including a synthetic FFmpeg clip that checks RGB order and the signed-byte trap. Probe matches 05 for all 4 clips; thumbnails upright (docs/img/p0_upright_thumbnails.jpg).
- P0.5 proven on the Mac (FFmpeg 7.1): all 4 MATCH, rotations 0/90/180/180, 1080p read at 175 fps; Mac thumbnails checked upright.
- P0.6 done: ColorConv (77/150/29 fixed point, gray exact, within 1 level of float), Pad (edge replicate right/bottom, crop). 25 tests.
- P0.7 code done: Clock (index-based deadlines, tested with fake time), Display (Swing, int raster, blocking paint, scale to fit), ProbeVideo --play with 30-frame step-back ring. 29 tests. Needs the Mac run for evidence (sandbox is headless).
- Mac play test: video2 12.74 s / 12.77 s, 0 to 1% late (pass). SAL 20.83 s, 100% late: about 48 ms per frame. Cause: Java2D rescaling an unmanaged image in software each frame. Fix P0-D8: own bilinear scaler (1.8 ms sandbox) and 1:1 draw; per-stage timing added to the play report. 32 tests.
- Next: Mac rerun of SAL --play to confirm, then P0.8 speed gate.
