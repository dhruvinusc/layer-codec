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
- P0.7 proven on the Mac: SAL 14.55 s / 14.57 s, 0.5 to 1.4% late; per frame decode 5.5, scale 1.6, paint 6 to 8 ms.
- P0.8 done on the Mac: display 0.7% late, SAD 139.6 ns, IDCT 15.3 ms all cores, zlib 10.3x with 15.3 ms inflate. All targets pass. Serial 1080p decode would be 37 to 39 ms, so P3 must pipeline.
- P0 closed except Dhruvin's explain-backs.

## 2026-11-10, Window A
- Types.java APPROVED by Dhruvin (P0-D9). P0 closed. Next: P1.1 full-search motion estimation, in a fresh Window A chat (this one is long).
- Parallel: Window B (P2) can start once PM writes its handoff; it uses fake checkerboard labels and must not touch P1 files or EncoderMain.

### Sandbox setup recipe for the next window (the Linux VM is per chat and starts empty)
The VM reaches GitHub but not Maven Central or services.gradle.org.
1. JDK 21: download `OpenJDK21U-jdk_aarch64_linux_hotspot_21.0.9_10.tar.gz` from github.com/adoptium/temurin21-binaries release `jdk-21.0.9+10`, untar into `$HOME/opt`.
2. Gradle 9.9.0: `gradle-9.9.0-bin.zip` from github.com/gradle/gradle-distributions release `v9.9.0`, unzip into `$HOME/opt`.
3. `unset JAVA_TOOL_OPTIONS`. Main code compiles offline (`gradle jar --offline`). Tests: javac main + test against `build-tools/junit-platform-console-standalone-1.11.4.jar` (made on the Mac by `./gradlew vendorJunit`), run the console launcher with `--scan-classpath`.
4. Sandbox `sed -i` drops the executable bit on launchers; re-run `chmod +x myencoder mydecoder` if touched.
5. Timings and playback count only on the Mac.

## 2026-11-10, Window A (P1, fresh chat)
- Sandbox rebuilt from the recipe; baseline 32/32. Test runner: javac main with -Xlint:all -Werror into a scratch dir (Mac `build/` untouched), console launcher `--scan-classpath`.
- Git deletion approved for this chat, so commits no longer leave .lock files.
- P1.1 done: `MotionEstimator.fullSearch` (SAD on Y, clamped window, tie-break P1-D1, early exit P1-D3), `Config.Motion`. 39/39 tests including an oracle comparison. Real clips (sandbox): SAL pan 6 to 8 px, Stairs 5 to 6 px, video2 needs R=32 (29% of blocks at the R=16 edge). RESULTS P1.1. Commit 03d33a1.
- Prototype files moved to `docs/img/naive_probe_overlay.jpg` and `tools/prototype/probe_motion.py` (P0-D5 updated). Commit dcab1a9.
- Explain-back pending: Dhruvin's P1.1 prediction (which blocks recover (-7,2) exactly; flat frame result).
- Next: P1.2 VizSeg v1 (overlay PNGs, arrows, stats CSV, overlay mp4).

## 2026-11-10, Window B (P2)
- [B/P2] P2.0 sync: head 6dfe7bf, sandbox rebuilt from the recipe (Temurin 21.0.9), 39/39 green (32 P0 + 7 from A's P1.1). Types used: Frame (int planes 0..255, row-major), VideoInfo (true w/h, padded w/h, fps num/den), Labels (macroblock grid, `get(col,row)`, 0 = bg). Pad.pad/crop for 1080 to 1088.
- [B/P2] Plan:
  1. P2.1 Dct: separable orthonormal 8x8, level shift 128, cosine table, zero-skip inverse; oracle = textbook double sum in src/test.
  2. P2.2 Quant: symmetric round(c / 2^n) to short, dequant q * 2^n, clamp pixels 0..255.
  3. P2.3 CmpFormat: byte table shown to Dhruvin, frozen only after his yes. Streaming writer, offset-table reader.
  4. P2.4 Zio: Deflater level 1, always end(), loop to finished().
  5. P2.5 DecodeCore: pure, thread-safe payload -> cropped RGB frame + 8x8 label map.
  6. P2.6 CodecHarness with fake checkerboard labels; P2.7 Psnr (overall, fg, bg); P2.8 CmpDump in the brief's text layout.
  7. P2.9 Mac measurements on video2 (120 f) and SAL (60 f) for (0,0) (2,5) (5,2) (1,7) (4,4).
  8. Codec constants (zlib level, magic, version) stay as named constants in my own classes, so frozen Config is not touched.
- [B/P2] Next: P2.1 Dct.
- [B/P2] 2026-11-11 P2.1 done: `codec/common/Dct.java` (forward, inverse, inverseZeroSkip; caller scratch, no allocation, no mutable state). `DctTest` 8 tests: textbook oracle on 200 random blocks within 1e-9; round trip within 1e-9; flat 200 gives DC 576 and AC 0; flat v gives DC 8(v-128); black -1024 / white 1016 (int16 safe); single cosine lands at index 3 (Parseval bound 4 after pixel rounding); offset read inside a larger plane; zero-skip bit-identical. 47/47 green (sandbox). DECISIONS P2-D1..D3.
- [B/P2] Next: P2.2 Quant.
