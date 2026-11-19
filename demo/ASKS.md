# Professor asks X, I do Y

Start the menu first: `bash demo/demo.sh`. Player keys work in the player window.

| Professor asks | I do | Proof or note |
|---|---|---|
| Replay it | `q`, then menu 3 (or 4 with the original) | |
| Pause | `space` | PlayerTest.pauseStepAndStepBackMoveOneFrameAndStopTheClock |
| Step forward | `right` | same test |
| Step back | `left` (up to 30 frames back from a cache) | FrameCacheTest.stepBackOneIsServedFromTheRing |
| Compare with the original | menu 4, then `s` | needs the mp4 as 2nd argument |
| Zoom in | `z`, drag to pan | 1:1 pixels, no scaling |
| Show the foreground | `d` | tint comes from the type byte in the .cmp |
| Swap n1 and n2 | menu 5; image `docs/img/p3r_SAL_swap_crop_x3.png` | SAL (2,5): fg 47.52 / bg 34.34 dB; (5,2): 34.40 / 47.72 dB (HANDOFF section 2) |
| Try other n values | `ln -sf video2.mp4 data/video2_17.mp4 && ./myencoder data/video2_17.mp4 1 7` then `./mydecoder data/video2_17.cmp data/video2.mp4` | n must be 0 to 10; about 20 s |
| Another clip | `./mydecoder data/SAL.cmp data/SAL.mp4` (also video1, Stairs) | all pre-encoded at (2,5) |
| Show the file format | menu 8 | header line, per frame sizes, then blocks as "type, 64 R, 64 G, 64 B" |
| Show motion vectors | open `docs/img/p12_mv_arrows_SAL218_video2_191.jpg` | live: `VizSeg data/video2.mp4 --at 100 --arrows` (flag exists, not yet run on the Mac) |
| How is it better than naive | menu 9 | frame 100 of video2: naive 29.6% foreground, final 24.3% (demo run Nov 19); whole clip 16.56% vs 7.25% (HANDOFF section 2) |
| Show a failure honestly | Stairs: distant walker missed in 74 of 346 frames (moves about 2 px vs the camera). video2: near fence slides past (parallax). Image `docs/img/p1_parallax_video2_off.jpg` | parallax filter exists, OFF by default |
| Run the tests | `./gradlew test` (or one test: see demo/TESTS.md) | 234 tests pass (HANDOFF) |
| How fast is decoding | menu 7 | video2, 4 workers: 414 / 511 / 435 fps baseline / mode 1 / mode 2 (demo run Nov 19); need 30 |
| Extra credit | menu 6 | video2 (2,5): 85.4 / 60.9 / 54.5 MB; mode 1 has identical pixels, mode 2 within 0.24 dB (HANDOFF section 2) |
