# Study guide (Tutor, started Nov 16)

Short version of each tutor topic, in order. Every number is a Mac number from docs/RESULTS.md unless marked.

## 1. Big picture

- A video is 30 pictures a second. Compression throws away detail the eye will not miss. Normal codecs throw away the same amount everywhere. This project throws away less where the eye looks (the moving subject) and more elsewhere.
- For every 16x16 macroblock of every frame the encoder asks: moving subject (foreground) or scene (background)? It decides from motion only. Arrows that match the camera's motion are background. Connected clumps of arrows that disagree with the camera and agree with each other are foreground.
- Each 8x8 block is turned into 64 frequency numbers (DCT, on R, G and B). Each number is rounded to a multiple of 2^n: n1 for foreground, n2 for background. Small n = fine rounding, sharp picture, big file. Large n = coarse, blocky, small.
- The .cmp stores n1, n2, then for every 8x8 block one type byte and 192 coefficients. The decoder reads only that file, multiplies back, inverts the DCT, plays at the source fps with pause and step.
- Analogy: theater lighting. Spotlight on the actor, dim light on the set; the bits are the light. Breaks because our encoder is blind and must find the actor from motion, and gets fooled by parallax and uncovered background.
- Worked example: SAL, 1920x1080 padded to 1920x1088 = 8160 macroblocks per frame; 1.07% foreground, about 87 tiles (the walker). At (2,5): walker 47.52 dB, wall 34.34 dB. At (5,2): 34.40 / 47.72 dB. The flip is the whole project in one measurement.
- Code: `myencoder` is a bash launcher that pins Java 21 and runs `codec.encoder.EncoderMain`. EncoderMain.encode(): open clip with VideoIO, pad with Pad.toMacroblocks, push the Y plane into Segmenter.Run (labels come out one frame late, so padded frames wait in a deque), then FrameEncoder.encodeParallel writes each frame with its labels through CmpFormat.Writer.
- Decision: Java 21 over C++ (defend it better, Swing and zlib built in; cost is speed, solved by frame-parallel decode). Rejected: YCbCr pipeline (brief says DCT on R, G, B; Y is for motion only), content detectors (B9: motion only), background = zero motion (all four clips have a moving camera).
- Proof: 234/234 tests; all 4 clips x 3 modes play with 0 dropped frames, within 0.10% of clip length; worst encode 130 s (SAL 5,2), under the 10 min E4 budget; 1080p decode 77 to 82 fps.
- Say: "The brief asks me to spend bits unevenly between a moving subject and its background; I find the subject from motion alone, round its DCT coefficients by 2^n1 and everything else by 2^n2, and the decoder rebuilds it live from one file."
