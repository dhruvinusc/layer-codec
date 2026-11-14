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
- [C/P3] 2026-11-12 Dhruvin asked to skip written explain-backs in this window; they move to his PM and Instructor/Tutor sessions (P3.1 step-back question, P3.2 packed/boundary question still owed there).
- [C/P3] 2026-11-12 P3.3 code done: `codec/decoder/Player.java` (index deadlines, drop-to-newest-due, late > half period, pause/step/back, pause on last frame, restart from the end, report with first/last 100 frames), `Display` packed show + status line (P3-D6), `DecoderMain` plays by default + hidden `--workers N`. Tests: PlayerTest 5 (fake clock: on time, slow decode drops but holds 4.0 s, pause/step/back, resume no drops, status text), DisplayScalerTest packed = planes, DecoderMainTest workers parse. 120/120 green (sandbox). Headless run exits cleanly with "no screen available". DECISIONS P3-D5, D6; decision log P3-L7.
- [C/P3] Next: Mac playback runs (commands in chat), 4 vs 6 workers; then P3.4.
- [C/P3] 2026-11-12 Mac run 1 of the player: durations 9 to 17% short, drops all at the start (start-up counted as lag); 6 workers 2.5x to 3x more late frames than 4. Recorded as-is in RESULTS P3.3 run 1. Fix P3-D7 pre-roll + test (121/121 green). New test file `out/p3/video2_full_2_5.cmp` (383 frames, 152 MB). Decision log P3-L8. Next: Mac run 2, then decide the worker default.
- [C/P3] 2026-11-12 Mac run 2 (after pre-roll): all durations within 0.6%; 1080p late 8 to 12% with 4 workers vs 27 to 45% with 6. Default workers changed to 4 (P3-D8, decision log P3-L9). 121/121 green. Dhruvin noticed the SAL test files are only the first 60 frames (same 2 s opening every run, so all warm-up): steady state needs a longer file. Sandbox CodecHarness for SAL 200 frames hit the 3-minute call limit and left an unfinished `out/SAL_p2.cmp` (311 MB, footer 0); the Mac run overwrites it. Next: Mac run 3 on SAL 200 frames; keys and status line check.
- [C/P3] 2026-11-12 Mac run 3 on SAL 200 frames: 4 workers (run first, right after the 362 MB encode) collapsed in frames 100..199 (94 dropped, mean wait 38 ms); 6 workers perfect (0 drops, 0 late, +0.08%). First run of each pair has always been the worse: runs 1 to 3 are not a fair 4 vs 6 comparison. Added hidden `--trace file.csv` (per-frame due/shown/wait/dropped/late) + tests (123/123 green). Wrote `out/p3/run4.sh` (git-ignored): 60 s cool-down, order 4,6,6,4, 20 s pauses, GC logs, traces, 200-frame bench, thermal state; output in out/p3/run4.txt for Window C to read. P3-D8 default (4) is provisional until run 4.
- [C/P3] 2026-11-12 Mac run 4 (fair 4 vs 6) read from out/p3: all runs 0 drops (verified per frame in the traces), durations within 0.13%; 4 workers 3.5% late average vs 6 workers 5.75%; steady state (frames 100..199) 0 to 1 late with 4 workers. Late frames are JIT warm-up in the first 2 s. GC pauses small (max 25 ms). Run 3 collapse not reproduced. P3-D8 (4 workers) confirmed; decision log P3-L9 proof updated. Remaining for P3.3: Dhruvin's keys/status check.
- [C/P3] 2026-11-12 P3.3 DONE. Dhruvin's visual check on the Mac: pause, step forward, step back, resume and status line "smooth". His run showed a report flaw: after a pause the window lines printed "shown 0 ... NaN fps" (paint times stop being recorded after the first pause). Fixed: windows print "not measured" when interrupted; PlayerTest asserts no NaN. 123/123 green.
- [C/P3] P3.3 evidence: RESULTS P3.3 runs 1 to 4 (durations within 0.13%, 0 drops, steady-state 1080p 0 to 1% late with 4 workers), PlayerTest (6 tests on a fake clock), traces out/p3/r4_trace_*.csv. Next: P3.4 side by side + 1:1 zoom.

## 2026-11-12 evening, Window A (P1), BATON MODE (Dhruvin away about 2 h)
- [A/P1] STATUS at start: P1.1 done (03d33a1, full search on Y with tie-break and early exit, 39 tests at the time; RESULTS "P1.1" sandbox). P1.2 to P1.10 not started. Repo head b6987f5, decision-log P0 done (4dafeb9), P1 log missing.
- [A/P1] Window A work continues in the original Window A chat (the fresh P1 chat has been idle since Nov 10).
- [A/P1] P1.2 v1 done: VizSeg renders label tint (green background, red/blue/... per region) and MV arrows along (-dx, -dy), hand-drawn on the pixel array (ImageIO only). Options: --at, --arrows, --range, --out. Evidence docs/img/p12_mv_arrows_SAL218_video2_191.jpg: SAL background arrows uniform horizontal (pan), walker irregular, tree leaves noisy; video2 background flows, joggers nearly still. 126 tests.
- [A/P1] P1.2 deferred parts (added as their inputs exist): `--naive` after P1.5 (needs the homography), stats CSV after P1.3 (needs fast ME over all frames), overlay mp4 later (optional).
- [A/P1] P1.3 done (54bb5b1): hierarchical search with 3 coarse starts, +/-2 refine, extra candidates, +/-1 polish, lambda 4. Agreement vs full search on textured blocks: video2 95.8%, video1 91.7%, Stairs 91.8%, SAL 89.8% (0.2 under target; brick periodicity, see RESULTS P1.3). Sandbox 37 ms (480p) and 193 ms (1080p) per frame. VizSeg --me-check added. 131 tests.
- [A/P1] P1.4 done: Segmenter.reliable + SegConfig (variance >= 100, MAD <= 12). Reliable: video1 31.4%, video2 59.4%, Stairs 64.3%, SAL 68.5%. 134 tests.
- [A/P1] NEXT: P1.5 GlobalMotion (RANSAC homography, Hartley normalization, affine/translation fallback), then `VizSeg --naive` and naive overlays at 20/50/80% saved to docs/img/naive_* BEFORE any segmentation tuning; then the stats CSV (needs whole-clip ME). Open a fresh Window A chat for P1.5 (this chat is long): H1 opener plus "Resume from docs/WORKLOG.md".
- [A/P1] Sandbox recipe note: this chat reused its own $HOME/opt; a fresh window rebuilds from the recipe above.

### PARCEL FOR PM [A/P1, Nov 12 baton]
1. `Config` is frozen, but H1 says thresholds live in one config record. P1 thresholds now live in `MotionEstimator.HierParams` and `Segmenter.SegConfig` (owned files). Accept, or unfreeze `Config` for A?
2. P1.3 acceptance: SAL agreement 89.8% vs the 90% target (3 of 4 clips pass; real misses 4.0%). Accept as is with the brick-periodicity explanation, or require more search work in P1.9?
3. VizSeg deviates from H1's option list for now: `--at f1,f2` instead of `--frames a:b`; `--naive`, stats CSV and overlay mp4 not yet built (need P1.5 and whole-clip ME). Overlay mp4 would pipe frames to ffmpeg from a tool (00 section 4 allows debug mp4s in tools); confirm.
4. Dhruvin's personal files still in the repo root (2 docx, 1 xlsx); not touched (06 D8).

### QUESTIONS FOR DHRUVIN [A/P1, queued during baton]
1. P1.1 (still pending from Nov 10): predict which blocks recover a (-7, 2) shift exactly, and what full search returns on a completely flat frame.
2. Why does a 30 px move need only a +/-8 search on the quarter-size image?
3. Why does the hierarchical search keep 3 coarse guesses instead of 1? What was the cost?
4. On SAL, a wider full search found LOWER-error matches that we still call wrong. Why can the lowest error be the wrong vector on a brick wall?
5. What does lambda do, and when does it change the answer?
6. Why is video1 only 31% reliable, and what happens to the unreliable blocks?

## 2026-11-12/13, Window C (P3, baton)
- [C/P3] INCIDENT Nov 12 17:28: a duplicate Window C chat was opened. To stop collisions, Dhruvin discarded the uncommitted P3.4 start (`git checkout -- Display.java`, removed `DisplayViewTest.java`). Commits were safe (latest b6987f5 from C, bfb8b16 from A). PM confirmed Nov 13 12:20 that this chat is the only Window C. P3.4 redone from this chat's own context. Decision log P3-L10.
- [C/P3] 2026-11-13 P3.4 code done: `Display` view state (s side by side, z 1:1 zoom, d overlay, mouse-drag pan; pure `Display.View`), `Display.showLayers(...)`, `Scaler.applyPackedInto`; `DecoderMain.OriginalVideo` (VideoIO forward reads, 30-frame packed ring, reopen past the ring, size check); window two frames wide when an original is given. Tests: DisplayViewTest 10, OriginalVideoTest 3. 147/147 green (sandbox, full suite). Real-data check (scratch program in the sandbox, not committed): SAL frame 30 through Display.View, images `docs/img/p3_SAL_n2_5_side_1to1.png`, `p3_SAL_n5_2_side_1to1.png`, `p3_SAL_n2_5_overlay_1to1.png`; looked at them: halves aligned, same region at 1:1, tint follows the checkerboard labels. DECISIONS P3-D9, P3-D10; decision log P3-L11.
- [C/P3] Mac checks queued for P3.4: run with the original, try s, z, drag, and step back past 30 frames (see BATON REPORT).
- [C/P3] 2026-11-13 P3.4b code done: pre-roll repaints frame 0 15 times (WARM_PAINTS) before the clock starts. Tests: PlayerTest prerollWarmsThePaintPathBeforeTheClockStarts, quitDuringPrerollEndsCleanly; fastDecode test updated for the warm-up paints. Decoder package 39/39 green. DECISIONS P3-D11, decision log P3-L12. Mac measurement queued (run 4 script again, compare whole-clip late % with run 4).
- [C/P3] 2026-11-13 P3.5 done: overlay (d key) shipped in the P3.4 code; evidence image `docs/img/p3_video2_n2_5_overlay.png` (video2 frame 60, plain | overlay, rendered through Display.View by a sandbox scratch program; looked at it: upright, tint follows the checkerboard labels). Decision log P3-L13. Mac check queued (press d in the real window). Note: the first commit attempt timed out when the Mac went to sleep and never ran (checked: no duplicate entries, no lock); redone once.
- [C/P3] 2026-11-13 P3.6 done: `DecoderMain.run(argv, err)` returns the exit code (0 / 2 usage / 1 file or machine); main just exits with it. Usage lists s, z, d. DecoderMainErrorsTest 10 tests (all error paths fail before any window). Full suite 168/168 green in the sandbox (includes Window A's uncommitted GlobalMotion files, compiled read-only, not staged). DECISIONS P3-D12, decision log P3-L14.

### PARCEL FOR PM [C/P3, baton Nov 13]
1. `OriginalVideo` is nested in DecoderMain to respect H3 ownership; a separate file `codec/decoder/OriginalVideo.java` would be cleaner. Approve a new file?
2. If P3.4b does not bring whole-clip late under 2%, next lever: `-Xms1g` (or similar) in the mydecoder launchers so heap growth does not cause early GC pauses (run 4 GC logs: 8 to 106 pauses per run, max 25 ms). Small launcher change.
3. With an original, the mp4 is read on the player thread (about 6 ms per 1080p frame); timing runs are made without it. A background reader would remove that at the cost of more code.
4. docs/img gained 4 P3 images (about 2.7 MB). Fine for the report; flag if repo size matters.
5. `out/p3/p34_fit_side_check.png` (about 1 MB, git-ignored) can go with the other deletable test files.
6. Window A's `GlobalMotion.java` and `GlobalMotionTest.java` are uncommitted in the tree (their WIP); they compile and their tests pass in the sandbox. Not touched by C.

### QUESTIONS FOR DHRUVIN [C/P3, baton Nov 13]
1. Predict: at (2,5) in 1:1 side by side, where will the decoded half look worse than the original, and where the same?
2. Why does the 1:1 zoom show artifacts that the fitted view hides?
3. How does the overlay know which blocks are foreground without any segmentation code?
4. Why does zoomed side by side show the same region in both halves?
5. Why warm the paint path before starting the clock, and why is that not cheating the measurement?
6. Stepping back 35 frames with the original open: what happens in each of the two 30-frame rings?
7. Give the decoder an unfinished .cmp: what does it print, and how does it know?
8. Still owed from Nov 12: step back before the cache; packed pixels and 100 back to 50; drop vs slow down; why pre-roll; why 4 workers beat 6.

### MAC CHECKS QUEUED [C/P3]
1. P3.4 + P3.5: `./gradlew build` then `./mydecoder out/p3/SAL_2_5.cmp data/SAL.mp4`: press s, z, drag, d, left arrow 35 times, q. Reply "view ok" or what looked wrong; also whether the window fits the screen.
2. P3.4b: `bash out/p3/run4.sh` again; compare whole-clip late % with run 4 (4 workers averaged 3.5%; goal under 2%).
3. P3.6: `./mydecoder data/SAL.mp4` should print "error: not a .cmp file ..." and exit 1 (echo $?).
- [B/P2] 2026-11-13 Window B: P2 decision log is owned and committed by Window C (6f1c897, P2-L1..L15). B's uncommitted duplicate draft out/decision-log-draft/P2-compression.md (created by B in this session, never committed) deleted. Window B has no open work; P2.X explain-back is with Dhruvin and the Tutor.

## 2026-11-13, Window A (P1, fresh chat)
- [A/P1] Sandbox rebuilt (Temurin 21.0.9 from the recipe); runner script compiles main + tests into a scratch dir. Background jobs do not survive between device_bash calls in this sandbox: run long tools in the foreground, one clip per call (under 3 minutes each).
- [A/P1] Delete permission granted in this chat ONLY so git can remove its own lock/temp files after Window A commits (00 section 2a). The first commit (0c7c64b) ran before the grant and left `.git/HEAD.lock` and `.git/objects/maintenance.lock`; Dhruvin cleared them. Window A has deleted no files.
- [A/P1] PM answers to the Nov 12 parcel logged in DECISIONS (thresholds in HierParams/SegConfig/GmConfig; SAL 89.8% accepted with real misses beside it; `--at` ok; ffmpeg pipe for debug mp4 ok); decision log P1-L10.
- [A/P1] P1.5 done: `GlobalMotion` (RANSAC homography, Hartley normalization, affine then translation fallback, corner sanity check, seed 576) + `GlobalMotionTest` 9 tests (0c7c64b). `Segmenter.residuals`, `Segmenter.naive`, `Segmenter.components` (4-connected BFS, scan order) + `SegmenterNaiveTest` 4 tests. `VizSeg --cam-stats [--step k]`, `--naive`, `--sheet`. 172/172 green (8968dd7).
- [A/P1] Evidence: RESULTS "P1.5 camera motion" (homography on 1679 of 1680 pairs, mean inliers video1 0.77, video2 0.74, Stairs 0.90, SAL 0.85; at or above doc 05) and "P1.5 naive baseline overlays" (docs/img/naive_*.jpg, saved before tuning, checked by eye). Decision log P1-L11, P1-L12. SPEC_CHECKLIST B6, B11 annotated partial.
- [A/P1] Known: video2 frame 30 falls back to translation (18% inliers); frames 60, 150, 180 near 38%. Handled by the P1.8 temporal vote.
- [A/P1] Note: `docs/WORKLOG.md` had a staged one-line [B/P2] entry; PM approved (Nov 14) committing it together with this entry (Window B is closed).
- [A/P1] NEXT: P1.6 residual grouping (consistency rule, size and coherence filter) in Segmenter; compare against the naive sheets.

## 2026-11-14, Window A (P1)
- [A/P1] P1.5 accepted by PM. P1.6 approach approved.
- [A/P1] P1.6 done: `Segmenter.eligible`, `cameraExplains`, `foregroundEligible`, `group` (consistency growth), robust `spread`; `SegConfig` extended (candMinVar 20, camSlackMad 1.0, camSlackRel 0.15, resPx 2.5, consPx 2, consMinMag 4, consAngleDeg 30, minRegionBlocks 4, minRegionFrac 0.002, maxSpreadPx 3). `VizSeg --seg`. Tests `SegmenterGroupTest` (7), `SegmenterCameraTest` (3). 185/185 green.
- [A/P1] Evidence: RESULTS "P1.6" (per-step foreground table, walker diagnostics), `docs/img/p16_*.jpg` checked by eye. DECISIONS P1-D15..D18, decision log P1-L13..L15.
- [A/P1] Mac connection dropped twice on Nov 13 (sleep). Keep `caffeinate -dims` running.
- [A/P1] NEXT: Stairs f69 miss (time box 2 h, else known limit), then P1.7 fill.
- [A/P1] 2026-11-14 noon: removed 3 stale git locks left by my P1.6 commit (HEAD.lock, next-index-15.lock, objects/maintenance.lock); pgrep showed no git running; Dhruvin said "clear locks"; PM standing permission from now on.
- [A/P1] Stairs f69 miss: known limit (RESULTS "P1.6 known limit", P1-D19, P1-L16). P1.7 fill done (vote + hole fill, dilation off): `Segmenter.fill`, `SegmenterFillTest` 6 tests, 191/191; RESULTS "P1.7 fill", docs/img/p17_*.jpg, P1-D20, P1-L17.
- [A/P1] 2026-11-14 P1.8 done: `Segmenter.Temporal` (2-of-3 vote through motion), `Segmenter.Run` (streamed whole-clip pipeline, FrameStats), `VizSeg --stats`. `SegmenterTemporalTest` 4 tests, 195/195. RESULTS "P1.8" (whole clips, all 4), docs/img/p18_*.jpg, P1-D21, D22, P1-L18. NEXT: P1.9 tuning on all 4 clips together (video2 joggers and fence first).
- [C/P3] 2026-11-13 (late entries; WORKLOG had uncommitted edits from Window A at the time): P3.5c `OriginalVideo` moved to its own file (1647f15). P3.4c background reader for the original (914e8f1): 8 ahead, 30 behind, restart from frame 0 on a long step back; 175/175 green. Mac: with the original 0 drops, 30.0 fps (2127da9). Two run4 re-runs did not count (loaded Mac; 1.4 to 1.8 s whole-process freezes). Decision log P3-L15.
- [C/P3] 2026-11-14 Paper-only session (no builds; Window A needs the CPU): P3 END-OF-PART REPORT draft `docs/p3_report_draft.md` (pending: step back 35 with the original, quiet run4 for P3.4b, P3.7 thermal on D9). P4 design proposal + DECISION REQUEST `docs/decision-log/P4-extra-credit.md` (P4-L1: options A full / B zigzag + run-length only / C none; proposed B first, A with a Nov 22 go/no-go).
- [A/P1] 2026-11-14 P1.9 step 1: 4-clip sweep (scratch tool $HOME/scratch/diag/Sweep.java, not committed), camSlackMad 1.0 -> 2.0 adopted after whole-clip eye check; "slack 2 + min 8 blocks" rejected (erased joggers and skateboarder). RESULTS "P1.9 tuning, step 1", docs/img/p19_step1_*.jpg, P1-D23, P1-L19. 195/195. NEXT: P1.9 step 2 (video2 fence/tree parallax; SAL edge frames 409 to 427), then P1.10.
- [A/P1] 2026-11-14 FLAKY TEST (not Window A's): `codec.decoder.OriginalVideoTest.forwardSkipRingHitAndReopenAllGiveTheSameFrameAsAStraightRead` failed 1 of about 12 full-suite runs in the sandbox ("expected: <1> but was: <2>"), passed on every rerun; looks like a thread-timing race in the P3.4c background reader test. Window A did not touch it. Parcel for PM / Window C.
- [C/P3] 2026-11-14 Flaky OriginalVideoTest (Window A's note, 1cb4bfe) fixed: race between claiming and storing a frame in the background reader (P3-D14). New deterministic regression test `askingForAFrameWhileItIsBeingPackedDoesNotRestartTheMp4`. Proof: OriginalVideoTest 20 runs in a row, 7/7 each, 20/20 (sandbox, nice'd). Decoder package green.
