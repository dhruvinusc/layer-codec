# Worklog

Running handoff between build windows. Newest entry at the bottom.

## 2026-11-09, Window A (P0)
- P0.1 done. Mac: macOS 14.2.1 arm64, JDK 20 default plus Temurin 21 installed via brew cask, FFmpeg 7.1 (conda-forge, on PATH while conda base is active), git 2.46.0, Homebrew 6.0.6. Build sandbox (Linux VM): Temurin 21.0.9 and Gradle 9.9.0 pulled from GitHub; no Maven Central access.
- P0.2 done in sandbox: Gradle skeleton, launchers, stubs with argument validation. `gradle jar` green with -Xlint:all -Werror; launcher checks pass. Commit 0ceebe9.
- P0.3 done: SPEC_CHECKLIST, DECISIONS, WORKLOG, RESULTS, presentation/notes.md headings.
- Waiting on Dhruvin: `./gradlew build` on the Mac (tests are the P0.2 evidence), `./gradlew vendorJunit`.
- Prototype files (naive_overlay.jpg, probe_motion.py) not available; see DECISIONS P0-D5.
- Next: P0.4 Types.java interface review.
