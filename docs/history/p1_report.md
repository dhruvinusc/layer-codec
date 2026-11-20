# P1 Segmentation: End-of-part report (Window A)

```
END-OF-PART REPORT  P1  2026-11-14
Checklist IDs proven: B1 to B11 (docs/SPEC_CHECKLIST.md, evidence beside each)
Checklist IDs partial or missing: none for P1. C1 (8x8 blocks inherit real labels) is proven at wire-up.
Key results: see "Numbers" below
Decisions made: DECISIONS P1-D1 to P1-D24; decision log P1-L1 to P1-L20 (docs/decision-log/P1-segmentation.md)
DECISION REQUESTS pending: none (parallax filter decided OFF by PM, Nov 14)
Doc change requests: 03 D3 step 2 can say "homography with affine/translation fallback, fitted on reliable blocks only"; 05 camera-fit table can cite RESULTS "P1.5" (our inlier ratios match or beat the prototype)
Known issues and risks: see "Known limits" below
Explain-back: queued for the Tutor (list at the end)
Commit hash: see git log (last P1 commit)
TICKS: B1 B2 B3 B4 B5 B6 B7 B8 B9 B10 B11
```

## Numbers (sandbox, final settings, parallax filter off)

| Clip | Frames | ME ms/frame (hier) | Camera model | Mean inliers | Final foreground (max) | Regions/frame | Frames with no foreground | Wall, whole clip |
|---|---|---|---|---|---|---|---|---|
| video1 | 518 | 37 | homography 517/517 | 0.774 | 2.70% (17.5%) | 2.99 | 16 | 21 s |
| video2 | 383 | 37 | homography 381, translation 1 | 0.737 | 6.95% (22.8%) | 7.53 | 1 | 16 s |
| Stairs | 346 | 192 | homography 345/345 | 0.904 | 1.51% (5.9%) | 1.21 | 79 | 74 s |
| SAL | 437 | 192 | homography 436/436 | 0.853 | 1.07% (2.9%) | 1.76 | 11 | 93 s |

- Against the naive baseline (same vectors, same camera model, P1-L12): SAL brick-wall false foreground 11 to 12% down to about 1%; video2 23 to 25% down to about 7%; walkers found where naive missed most of them.
- Determinism: video2 labels identical across two runs (SHA-256 f568f6ac...). All four hashes in RESULTS "P1.10".
- Speed: whole SAL (motion + segmentation) 93 s in the sandbox, against the 3-minute target. Mac number pending (quiet-Mac run below).
- Images: before `docs/img/naive_*.jpg`, after `docs/img/p1_final_*.jpg`; every step in between `p16_*`, `p17_*`, `p18_*`, `p19_*`. Parallax slide pair `p1_parallax_video2_off.jpg` / `_on.jpg`.

## The pipeline in one line per step (what the code does, in order)
1. Hierarchical block matching on Y, 16x16, against the previous frame, seeded with the previous field and camera vector (P1-L5 to L8).
2. Reliable blocks: texture >= 100 and MAD <= 12 (P1-L9). Only these fit the camera.
3. Camera motion: RANSAC homography, fallback affine, then translation (P1-L11).
4. Candidates: good match, texture >= 20, not explained by the camera's vector, residual > 2.5 px (P1-L13, L15, L19).
5. Grouping: 4-connected, neighbours join only if residuals agree; regions >= 0.2% of blocks and robust spread <= 3 px (P1-L13, L14).
6. Fill: 3-of-4 neighbour vote and enclosed holes (P1-L17).
7. Temporal: foreground in 2 of 3 frames, read through motion (P1-L18). Frame 0 copies frame 1.

## Known limits (honest list for the report and Q&A)
- Distant slow walker (Stairs frames 1 to 71): about 2 px/frame against the camera, at the noise floor of block matching (P1-L16).
- Parallax on video2: near fence and trees keep coherent leftover motion; filter built, kept off (P1-L20).
- Joggers' limbs on video2 move in mixed directions, so they are found only in part.
- Flat, dark clothing that is not enclosed by a region (Stairs trousers) stays background.
- SAL frames 409 to 427: the walker at the right edge is missed.
- Region colours in overlays can change between frames (ids not matched; the .cmp stores only 0/1).

## Wire-up notes (W1: the exact calls EncoderMain makes)
Everything below already exists and is tested; EncoderMain only connects it.

```java
try (VideoIO.Reader in = VideoIO.open(args.input())) {
    VideoInfo info = in.info();
    Header h = Header.baseline(info, args.n1(), args.n2(), true);      // zlib on (03 D7)
    // optional, off by default: --mode rle sets mode 1 in h.flags() and encodes with RunLength (P4-B)
    Segmenter.Run seg = new Segmenter.Run(info.paddedW(), info.paddedH(),
            Segmenter.SegConfig.DEFAULT, GlobalMotion.GmConfig.DEFAULT, MotionEstimator.HierParams.DEFAULT);
    ArrayDeque<Frame> waiting = new ArrayDeque<>();                     // padded frames waiting for labels
    ExecutorService pool = Executors.newFixedThreadPool(cores);
    try (CmpFormat.Writer out = new CmpFormat.Writer(args.output(), h)) {
        Frame f = Frame.allocate(info.width(), info.height());
        while (in.read(f)) {
            Frame p = Pad.toMacroblocks(f);                              // new padded frame each time
            waiting.add(p);
            for (Labels l : seg.push(ColorConv.luma(p))) {
                out.writePayload(FrameEncoder.encodeParallel(h, waiting.poll(), l, pool, 2 * cores));
            }
        }
        for (Labels l : seg.finish()) {
            out.writePayload(FrameEncoder.encodeParallel(h, waiting.poll(), l, pool, 2 * cores));
        }
        if (!waiting.isEmpty()) {                                        // 1-frame clip: no motion, all background
            Frame p = waiting.poll();
            out.writePayload(FrameEncoder.encodeParallel(h, p, new Labels(info.mbCols(), info.mbRows(),
                    new byte[info.mbCols() * info.mbRows()]), pool, 2 * cores));
        }
    } finally {
        pool.shutdown();
    }
}
```
- Labels come out one frame behind, and two at once after frame 2 (frame 0 copies frame 1), so at most 3 padded frames wait (about 75 MB at 1080p).
- Labels and frames stay in order because both are FIFO; the encoder never needs frame numbers.
- Optional, off by default: `--labels` to also write `out/<clip>.labels` with LabelsIO (handy for the demo and for checking the hash); `--parallax DEG` only if PM ever wants it in the encoder (today it is a VizSeg flag only).
- E4 check after wire-up: `time ./myencoder data/SAL.mp4 2 5` plus decode must stay under 10 minutes on the Mac. Expect about 1.5 min segmentation plus about 1 min compression (P2.9).

## Quiet-Mac run for Window A (D13, run when no window is busy)
From the repo root, with conda base active:
```
./gradlew -q jar
java -Xmx3g -cp build/libs/layer-codec.jar codec.tools.VizSeg data/SAL.mp4 --range 16 --me-check
java -Xmx3g -cp build/libs/layer-codec.jar codec.tools.VizSeg data/video2.mp4 --range 32 --me-check
time java -Xmx3g -cp build/libs/layer-codec.jar codec.tools.VizSeg data/SAL.mp4 --stats --labels --out out/mac
```
Pass: hierarchical ME at or under 200 ms per 1080p frame; whole-SAL wall time under 3 minutes; the printed SAL hash should equal the sandbox hash `7f203b4c6ce044a7...` (Java floating point is deterministic across machines, so a different hash would point to a real bug). Paste the four output lines into Window A.

## Explain-back questions for the Tutor (P1)
1. MV sign convention (03 D12) and what breaks if it is flipped.
2. Why +/-8 at quarter size covers 30 px; why 3 coarse guesses.
3. Why the lowest match error can be the wrong vector on bricks; what lambda does.
4. A homography in one sentence; why Stairs needs it; RANSAC in one sentence; why a fixed seed.
5. Why the camera fit uses strict reliable blocks but candidates use a looser gate.
6. What "explained by the camera" means and why it is still motion-only.
7. Why consistent direction removes brick aliases; why the spread is a median.
8. Why fill but not dilate; why the 2-of-3 vote follows the motion vectors.
9. Why the distant Stairs walker is missed and how multi-frame accumulation would fix it.
10. Why the parallax filter is off even though its numbers are better.
