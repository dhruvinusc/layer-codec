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
- [B/P2] 2026-11-11 P2.2 done: `codec/common/Quant.java` (step, quantize, dequantize, block versions, toPixel; n range 0..10 enforced). `QuantTest` 8 tests: lesson examples at n=3 (13->2->16, -12->-2->-16, 3->0, 576->72->576, 5->1, 2->0); symmetric halves; error <= step/2 and mean grows with n; rounding ~half of truncation error; int16 overflow caught; bad n rejected; toPixel clamp; full block round trip at n=0 >= 45 dB. Sandbox probe on 500 random-noise blocks (worst case, no energy compaction): n=0 58.95 dB max err 1; n=2 46.63 dB; n=5 28.97 dB. 55/55 green. DECISIONS P2-D4.
- [B/P2] Next: P2.3 CmpFormat byte table, needs Dhruvin's yes before freezing.
- [B/P2] 2026-11-11 P2.4 done: `codec/common/Zio.java`. `ZioTest` 6 tests: random bytes round trip, empty input (caught a real loop bug, fixed), quantized gradient coefficients compress > 5x and round trip, truncated / wrong length / garbage all throw. 61/61 green (sandbox). DECISIONS P2-D5.
- [B/P2] Next: P2.3 CmpFormat exactly to 03 D7 (frozen v1), one test per validation rule.
- [B/P2] 2026-11-11 P2.3 done to frozen 03 D7 (v1, PM + Reviewer additions): `codec/common/CmpFormat.java`. `CmpFormatTest` 17 tests: 48-byte header with patched count/footer and reserved 0; 3-frame round trip identical with and without zlib, random access out of order; scan line order at 385 bytes per block; 8x8 inherits macroblock label (region 255 -> 1); zlib off stores equal lengths. One test per validation rule: magic, version, unknown flag bits, unknown mode, n1 range, n2 read unsigned (255 not -1), block_type 2 and 0xFF, footer 0 = unfinished, zlib off length mismatch; plus truncated file, short file, corrupt zlib, writer-side rejects. 78/78 green (sandbox). DECISIONS P2-D6. SPEC_CHECKLIST C1..C6, D1 annotated partial.
- [B/P2] Next: P2.5 DecodeCore.
- [B/P2] 2026-11-11 P2.5 done: `codec/decoder/DecodeCore.java` (decode, decode with Scratch, decodeParallel; returns cropped Frame + 8x8 block_type map). `DecodeCoreTest` 7 tests: n=0 round trip max error <= 2 and cropped 30x10 from padded 32x16; lesson prediction (stored 3 -> 12 fg at n1=2, 96 bg at n2=5; pixels 130 / 140); block_type picks n and swapping n1/n2 swaps which blocks are clean; type map returned; parallel rows identical to serial; 16 concurrent decodes agree; bad block_type rejected. 85/85 green (sandbox). DECISIONS P2-D7.
- [B/P2] Open: the encoder-side transform (pad, DCT per channel, quantize by type) is needed by both CodecHarness and EncoderMain at wire-up. Proposal for PM: a new Window B file `codec/common/FrameEncoder.java` instead of burying it in a tool.
- [B/P2] Next: P2.6 CodecHarness.
- [B/P2] 2026-11-11 P2.6 + P2.7 done: `codec/common/FrameEncoder.java` (PM approved), `codec/tools/Psnr.java` (accumulator + CLI `Psnr <mp4> <cmp>`), `codec/tools/CodecHarness.java`. Tests: FrameEncoderTest 5, PsnrTest 4, CodecHarnessTest 3 (incl. synthetic ffmpeg clip end to end: two runs same SHA-256, n=0 >= 45 dB, (1,7) fg > bg + 5 dB and (7,1) reversed). 97/97 green. Real clips in sandbox (RESULTS P2.6): video2 (2,5) fg 47.47 / bg 32.67 dB, swapped mirrors it; n=0 59 dB; SAL 1080 padded and cropped correctly. Images checked by eye (upright, colors right, checkerboard blur visible at 1:1). zlib ratio explained by fg share (4.4x to 13.2x), consistent with P0's 10.3x. DECISIONS P2-D8, D9.
- [B/P2] Note: test resolves ffmpeg itself (FFMPEG env or PATH) because `VideoIO.ffmpegBin()` is package-private and VideoIO is frozen.
- [B/P2] Next: P2.8 CmpDump, then P2.9 Mac measurements (commands to Dhruvin).
- [B/P2] 2026-11-11 PM approved P2.6/P2.7. SPEC_CHECKLIST C2..C6 ticked with evidence, C1 stays partial (fake labels); RESULTS note that the exact PSNR mirror is a checkerboard artifact; video2 side-by-side copied to docs/img. Commit 7c46ad9.
- [B/P2] 2026-11-11 P2.8 done: `codec/tools/CmpDump.java` (header, frame table, first K blocks of frame f in the brief's `n1 n2` / `block_type c1..c64 x3` layout). `CmpDumpTest` checks n1 n2 line, 193 tokens per block, R/G/B positions. Real SAL frame: background block at n=5 is DC -14 then nearly all zeros. 98/98 green. DECISIONS P2-D10.
- [B/P2] Next: P2.9 Mac measurements (commands for Dhruvin).
- [B/P2] 2026-11-11 P2.9 done on the Mac (`out/p29.sh`, output `out/p29.txt`; first attempt failed because zsh does not word-split variables, fixed with a bash script). RESULTS "P2.9 Mac measurements": 5 settings x 2 clips, plus 1-thread and raw. Key: fg/bg layer effect confirmed; n=0 59 dB; same SHA-256 on 1 and 8 threads; zlib 3x to 9.6x; inflate ~14 ms, deflate ~38 ms per 1080p frame; 1080p decode 45.6 ms at n=0 > 33.3 ms budget, so P3 must decode frames ahead in parallel. Images docs/img/p29_*.
- [B/P2] Next: P2.X end-of-part report + explain-backs.

## 2026-11-11, Window C (P3)
- [C/P3] P3.0 sync: head a36bc0a, sandbox JDK 21.0.9 from the recipe (javac + vendored JUnit, no Gradle needed), 98/98 green, zero warnings.
- [C/P3] Test .cmp files from CodecHarness (fake checkerboard labels) in `out/p3/` (git-ignored): video2 first 120 frames and SAL first 60 frames at (2,5), (5,2), (0,0), (1,7). PSNR matches Mac P2.9 (video2 (2,5) fg 48.14 / bg 34.39; SAL (2,5) fg 47.73 / bg 34.48). CmpDump headers checked: SAL 1920x1080 padded 1088, 30/1 fps, 60 frames. Short clips on purpose: Mac disk has about 14 GB free. Note: the harness writes `out/<clip>_p2.cmp`, so the old P2.9 copies there were overwritten, then moved into `out/p3/`.
- [C/P3] APIs used: `CmpFormat.Reader(path)` (header(), frameCount(), payload(k): positional reads, inflates, safe from several threads); `DecodeCore.decode(header, raw, Scratch)` -> `Decoded(frame cropped to true size, types per 8x8 block)`, one Scratch per thread; `Display(title, w, h)` show(frame) blocks until painted, pollKey/waitKey, own bilinear scaler (P0-D8); `VideoIO.open(mp4)` streams upright frames for side by side.
- [C/P3] Plan:
  1. P3.1 DecodePipeline: pool of max(2, cores-2) workers, each with its own Scratch; reads payload(k) + decode; bounded index-ordered buffer (about 8 frames at 1080p, from heap); get(k) returns exactly frame k; seek(k) restarts the window; close() joins all threads.
  2. Tests: hash identical to serial DecodeCore for every frame; order under random worker delays; buffer never exceeds capacity; seek backwards; no live threads after close.
  3. P3.2 FrameCache: last ~30 shown frames for instant step back; beyond it, re-decode by footer offset.
  4. P3.3 Player on Display: steady clock t0 + i*den/num, drop late frames, counters on screen, keys space/right/left/q; no decoding on the event thread.
  5. P3.4 side by side + 1:1 zoom + pan; P3.5 overlay from block_type map; these need new keys (s, d, z, mouse) in Display, which Window C now owns.
  6. P3.6 DecoderMain CLI, clear errors, hidden --bench; P3.7 Mac measurements (commands for Dhruvin).
- [C/P3] Next: P3.1 DecodePipeline.
- [C/P3] 2026-11-12 P3.1 done: `codec/decoder/DecodePipeline.java` (workers = max(2, cores-2), capacity from heap, window + generation, error per frame, close joins threads). `DecodePipelineTest` 8 tests: every frame bit-identical to serial DecodeCore; order under random worker delays; peak buffered <= capacity (and fills to it); seek back and jump ahead give the right image; decode error reported for that frame only; close leaves 0 threads and later get throws; range checks; default sizes. Hidden `--bench` in DecoderMain (P3-D3) + parse test. 107/107 green, zero warnings (sandbox).
- [C/P3] Sandbox bench (4 cores, NOT authoritative): SAL n=0 serial 171 ms/frame; pipeline 1/2/4 workers = 132 / 51 / 33 ms/frame. Scaling works; Mac numbers pending (command in chat).
- [C/P3] Explain-back P3.1: Dhruvin's predictions right on ordering; throughput estimate corrected (45.6 ms was 8-core, single-core ~78 ms at n=0). His check led to P3-D1.
- [C/P3] Next: Mac bench, then P3.2 FrameCache.
- [C/P3] 2026-11-12 Mac bench recorded (RESULTS P3.1): SAL n=0 serial 71.9 ms; 6 workers 21.0 ms/frame = 47.7 fps (1.59x real time); (2,5) 4 workers 16.5 ms = 60.7 fps; scaling flattens past 4 workers. Default 6 kept.
- [C/P3] 2026-11-12 Decision log (00 section 12): backfilled `docs/decision-log/P2-compression.md` (P2-L1 to P2-L15, from DECISIONS P2-D1..D10 and 03 D6, D7) and `docs/decision-log/P3-decoder-player.md` (P3-L1 to P3-L4). Next: P3.2 FrameCache.
- [C/P3] 2026-11-12 Deletion promise (Window C, per 00 section 2a): delete permission was granted in this chat only so git can remove its OWN `.git/index.lock` at the end of a successful commit. Window C never deletes files by hand: no `rm` on repo files, data, out/ or locks. If a lock already exists, wait 5 s and retry up to 5 times, then stop and tell Dhruvin. Disclosure: on Nov 12 I removed one empty temp file I had created seconds earlier by mistake (`src/test/java/codec/decoder/DecoderMainTest.java.tmp`, never committed); no other deletions. Test .cmp files are moved or overwritten, never deleted; cleaning out/ is Dhruvin's call.
- [C/P3] 2026-11-12 PM notes for later steps: P3.7 plays FULL-LENGTH SAL at (0,0) and (2,5) and reports fps for the first 100 and last 100 frames (fanless Air, thermal slowdown; a 60-frame bench cannot show it). Needs about 2.5 GB of free disk for the two files. P3.2 adds a step-back test across the cache boundary (from frame 100 to 50). P3.3 also compares late % with 4 vs 6 workers (P3-L5).
- [C/P3] Decision log: P3-L5 (6 workers default). P2 backfill (P2-L1..L15) was already done in 6f1c897.
- [C/P3] 2026-11-12 Correction to the entry above titled "PM notes for later steps": those four points came from the Instructor/Tutor chat, NOT the PM. They are PROPOSALS pending PM approval: (1) P3.7 full-length SAL at (0,0) and (2,5) with fps for the first and last 100 frames (thermal check); (2) P3.2 step-back test across the cache boundary (100 to 50); (3) P3.3 late % with 4 vs 6 workers (Window C's own addition). Items 2 and 3 are inside P3 scope and cost little, so Window C will do them unless PM says otherwise; item 1 changes P3.7 and needs disk space, so it waits for PM.
- [C/P3] 2026-11-12 PM applied the 3 doc change requests and approved the 3 proposals (thermal P3.7, boundary test P3.2, 4 vs 6 workers P3.3). Re-read 00 and H3.
- [C/P3] 2026-11-12 P3.2 done: `codec/decoder/FrameCache.java` (ring of 30 packed 0xRRGGBB frames + type maps, evict oldest inserted, reuse buffers, miss decoded through DecodePipeline). `FrameCacheTest` 6 tests: step back 1 is a hit with no restart; 100 back to 50 gives 29 hits (99..71) + 21 re-decodes (70..50), all pixel-identical to serial decode, then 51..60 forward are hits; eviction order + buffer reuse; 30 x 1080p = 249.8 MB; packing keeps every value; bad capacity. 113/113 green, zero warnings (sandbox). DECISIONS P3-D4, decision log P3-L6.
- [C/P3] Explain-back pending: P3.1 (step back from 30 before the cache), P3.2 predictions (memory of 30 frames; 100 back to 50). Next: P3.3 Player.
