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
- Next: Dhruvin runs ProbeVideo on the Mac (FFmpeg 7.1 path), then P0.6 ColorConv + Pad.
