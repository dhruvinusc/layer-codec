# Slide notes: segmentation (Window A)

Seven slides, about 4 minutes. Each slide: what to put on it, images from `docs/img/`, what to say, the numbers. Numbers are Mac unless marked sandbox; sources in `docs/RESULTS.md` (section names in brackets) and `docs/decision-log/P1-segmentation.md` (entry ids).

---

## Slide 1. The segmentation pipeline (diagram)
**On the slide:** one left-to-right flow, seven boxes, one short label under each:
1. **Frame t and frame t-1, Y only** (brightness plane, padded to multiples of 16)
2. **Motion vector per 16x16 block** (hierarchical search: quarter size, half size, full size)
3. **Trusted blocks** (enough texture, good match)
4. **Camera motion** (RANSAC homography, fitted on trusted blocks only)
5. **Leftover motion = vector minus camera** (candidate if large and not explained by the camera)
6. **Group** (neighbours join only if they move the same way), **fill holes**
7. **2 of 3 frames vote** (followed along the motion vectors) **then labels**: 0 = background, 1+ = foreground

An arrow from box 7 to a small box "8x8 DCT blocks inherit the label, quantize with 2^n1 or 2^n2".
**Say:** "Motion vectors are normally used to predict frames. Here I reuse them to find which blocks belong to moving objects, then spend precision there."
**Numbers:** motion search 190 ms per 1080p frame, whole SAL segmentation 90.7 s [W2 / D13 Mac numbers].

## Slide 2. Motion vectors (optional, if time)
**Image:** `p12_mv_arrows_SAL218_video2_191.jpg`
**Say:** "On SAL the background arrows all point the same way, which is the camera panning; the walker's arrows differ. On video2 the background flows past while the joggers stay almost still, because the camera runs alongside them."
**Numbers:** fast search agrees with exact search on 89.9% (SAL) and 95.9% (video2) of textured blocks; true misses 1.6 to 4% [P1.3]. Most SAL disagreements are equally good matches on repeating bricks (P1-L10).

## Slide 3. Naive baseline (the "before")
**Images:** `naive_SAL.jpg`, `naive_video2.jpg` (three frames each, at 20%, 50%, 80% of the clip)
**Say:** "The simplest rule: a textured block whose leftover motion is over 3 pixels. It finds the walker, but the brick wall and the fence light up."
**Numbers (sandbox, the three frames):** SAL 12.1%, 11.1%, 3.8% of blocks foreground; video2 23.5%, 24.8%, 6.3% [P1.5 naive baseline].

## Slide 4. Final segmentation (the "after")
**Images:** `p1_final_SAL.jpg`, `p1_final_video2.jpg` (same three frames). Optional third row: `p1_final_Stairs.jpg`, `p1_final_video1.jpg`.
**Say:** "Same vectors and camera model. Three additions: neighbours must move the same way, the camera's motion must not explain the block just as well, and a block must be foreground in two of three frames. On SAL the wall is clean and the walker is found in all three frames."
**Numbers:**

| Clip | Naive, whole clip, same vectors (Mac) | Final, whole clip (Mac) |
|---|---|---|
| SAL | 8.68% | 1.07% |
| video2 | 16.56% | 7.25% |
| video1 | not run | 2.66% |
| Stairs | not run | 1.57% |

Say it as: "On the same vectors and camera model, SAL falls from 8.68% to 1.07% of blocks and video2 from 16.56% to 7.25%." [Mac re-measure (Nov 16)]

Layer effect with these real labels at (2,5): foreground 47.5 to 48.1 dB, background 34.0 to 37.6 dB, on all four clips [W2].
**Decision trail for Q&A:** P1-L13 (consistency), P1-L15 (camera test), P1-L17 (fill), P1-L18 (2 of 3 vote), P1-L19 (tuning, one rejected setting).

## Slide 5. Parallax: built, measured, kept off
**Images, side by side:** `p1_parallax_video2_off.jpg` (default) and `p1_parallax_video2_on.jpg` (filter at 30 degrees). Same frames.
**Say:** "The camera runs alongside the joggers. The fence close to the camera slides faster than the far background, so its leftover motion is large and very consistent, and the flat camera model calls it foreground. A filter that drops regions moving the same way as the camera's flow removes the fence and trees. But it would also remove a person walking against the camera's direction, and it did remove part of a pedestrian on video1. A blurred person is the mistake viewers notice, so the default is off."
**Numbers (sandbox):** video2 whole clip 6.95% (off) vs 4.15% (on) [P1.9 step 2]. P1-L20. The Mac default (off) is 7.25% [Mac re-measure (Nov 16)].

## Slide 6. Known limit: the distant walker on Stairs
**Image:** `p1_final_Stairs.jpg`, and point at the first panel (frame 69, walker at the top, no red).
**Say:** "At the start of Stairs the walker is far away and moves about 2 pixels per frame against the camera. Our camera model is accurate to about 0.7 pixels and block matching measures whole pixels, so that motion is at the noise floor. His dark jacket is also flat, and the search's smoothness term gives flat blocks their neighbours' vector, which is the camera's. I tried adding up his motion over several frames; it found almost nothing new and raised false foreground on video2, so I took it out."
**Numbers:** 74 of 345 analysed Stairs frames have no foreground (frame 0 copies frame 1), mostly at the start [Mac re-measure (Nov 16)]. Experiments: lower threshold, exact camera test, adaptive threshold [P1.6 known limit]; accumulated motion over frames [W3]. P1-L16, P1-L21.
**If asked how to fix it:** "In the motion search itself: a smaller smoothness penalty when a block clearly prefers its own vector, or smaller blocks for small objects. Both change every clip, so they need the whole tuning pass again."

## Slide 7. Numbers on this laptop
**On the slide:** a small table.

| Check | Result | Target |
|---|---|---|
| Encode full SAL (1080p, 437 frames) | 1 min 51 s | encode + decode under 10 min |
| Motion search per 1080p frame | 190 ms | 200 ms |
| Segmentation, whole SAL | 90.7 s | 3 min |
| Playback SAL, video1, video2 | 0 dropped frames at 30 fps | real time |
| Labels, same input twice | byte-identical (SHA-256) | deterministic |

**Say:** "Everything runs well inside the brief's 10 minutes on a fanless laptop, and the same input always gives the same labels."
**Footnote if asked:** a different FFmpeg version decodes slightly different pixels, so labels differ by about 0.1% of blocks between machines; the Mac is the reference.

---

## Image checklist (all in `docs/img/`)
- `p12_mv_arrows_SAL218_video2_191.jpg` (slide 2)
- `naive_SAL.jpg`, `naive_video2.jpg` (slide 3)
- `p1_final_SAL.jpg`, `p1_final_video2.jpg`, optional `p1_final_video1.jpg`, `p1_final_Stairs.jpg` (slides 4, 6)
- `p1_parallax_video2_off.jpg`, `p1_parallax_video2_on.jpg` (slide 5)
- Step-by-step images, if a "how it improved" backup slide is wanted: `p16_*` (grouping), `p17_*` (fill), `p18_*` (2 of 3 vote), `p19_step1_*` (tuning)
