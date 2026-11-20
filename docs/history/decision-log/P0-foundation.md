# Decision Log: P0 Foundation (Window A)

Plain-language record of every P0 choice that could reasonably have gone another way. Oldest first.
Technical source: repo `docs/DECISIONS.md` (P0-D1 to P0-D9) and Project doc 03 (D1, D5, D8, D11).

### P0-L1: Java instead of C++
- Date / window: Nov 9, Window A (DECISION REQUEST, approved by Dhruvin)
- Brief says: brief is silent on language; it only names `myencoder.exe` and `mydecoder.exe` (checklist G).
- The problem in plain words: we had to pick one language for the whole codec, and the original plan was C++.
- Options we considered:
  1. C++ with OpenCV : good, fastest code / bad, Dhruvin's second language, and OpenCV is slow to install on Windows.
  2. Java : good, Dhruvin can defend every line, and the window, keyboard and zip tools are built in / bad, tight loops about 1.2 to 2 times slower.
  3. Python : good, quick to write / bad, far too slow for 1080p video without heavy libraries.
- What we chose: Java 21 (the current long-term-support version).
- Why (plain words): the grade depends on explaining the code live, so fluency matters more than raw speed. We then measured that Java is fast enough (see P0-L10).
- Proof: P0 speed gate in `docs/RESULTS.md`; 1080p plays on time.
- Cost or limitation: the brief says `.exe`; we ship small launcher scripts instead. The professor has not confirmed Java yet.
- Say it in the presentation: "I chose Java because I can defend every line of it, and I measured early that it is fast enough for 1080p."
- Likely professor question: Isn't Java too slow for video? / Answer: Only the inner loops are slower, by about 1.5 times. The speed test showed every stage fits the 33 ms per frame budget once we use all 8 cores.

### P0-L2: Read the mp4 through FFmpeg as a separate program
- Date / window: Nov 9, Window A
- Brief says: "write your own display code"; reading mp4 files is not described (E1).
- The problem in plain words: Java cannot open mp4 files by itself, and writing an H.264 decoder ourselves is a whole project.
- Options we considered:
  1. FFmpeg run as a separate program, sending raw pixels through a pipe (a one-way data channel) : good, nothing to link, same on Mac and Windows / bad, FFmpeg must be installed.
  2. JavaCV (a Java wrapper around OpenCV and FFmpeg) : good, one dependency / bad, about 300 MB of platform-specific files.
  3. Write our own H.264 decoder : good, no dependency / bad, weeks of work unrelated to the brief.
- What we chose: FFmpeg as a separate program, asked for raw RGB pixels (`rgb24`, 3 bytes per pixel in R, G, B order).
- Why (plain words): it keeps the only outside tool in one small class (`VideoIO`), and RGB output means we never deal with the BGR (blue first) order that OpenCV uses.
- Proof: test `VideoIOTest.readsSyntheticClipInRgbOrder` (a pure red clip comes back with red high, green and blue low); all 4 clips read with the correct frame counts.
- Cost or limitation: the demo Mac needs FFmpeg; ours is the conda build, which only works while conda is active.
- Say it in the presentation: "FFmpeg only unpacks the mp4 into raw pixels; every algorithm after that is mine."
- Likely professor question: Is using FFmpeg cheating? / Answer: It only decodes the input file, like opening an image. Motion search, segmentation, DCT, quantization and the file format are all hand-written.

### P0-L3: Rotate frames ourselves instead of trusting auto-rotation
- Date / window: Nov 9, Window A
- Brief says: brief is silent on rotation (checklist G).
- The problem in plain words: phones store some videos sideways or upside down and add a note saying how to turn them. Stairs and SAL are stored upside down, video2 sideways.
- Options we considered:
  1. Let FFmpeg rotate automatically : good, no code / bad, behaviour differs between versions and tools, so frames could silently arrive upside down.
  2. Turn auto-rotation off and apply the rotation ourselves from the note : good, explicit and testable / bad, a little more code.
- What we chose: option 2. We read the rotation note (the "display matrix", or the older "rotate" tag as a fallback) and apply a matching filter: 90 degrees, 180 degrees or 270 degrees.
- Why (plain words): an upside-down frame would still "work", but motion vectors and the demo would be wrong. Explicit beats implicit. The two FFmpeg versions we use (4.4 and 7.1) even report the note differently.
- Proof: `docs/img/p0_upright_thumbnails.jpg` (the text on the Stairs steps reads correctly); test `VideoIOTest.rotationFromEitherFfmpegVersion`.
- Cost or limitation: only multiples of 90 degrees are supported; anything else stops with a clear error.
- Say it in the presentation: "Two clips are stored upside down and one sideways, so I rotate every frame explicitly and checked it with thumbnails."
- Likely professor question: Why not just use the auto-rotate option? / Answer: Its behaviour depends on the FFmpeg version. Doing it myself gives the same upright frames on every machine.

### P0-L4: Count frames by reading to the end
- Date / window: Nov 9, Window A
- Brief says: brief is silent.
- The problem in plain words: the mp4 claims a frame count, but that number can be wrong.
- Options we considered:
  1. Trust the stored frame count : good, known in advance / bad, a wrong count breaks the file or loses frames.
  2. Read until the video ends : good, always correct / bad, the count is only known at the end.
- What we chose: read until the end; the stored count is only a hint.
- Why (plain words): correctness first. The .cmp writer fills in the real count when it closes the file.
- Proof: all 4 clips show "MATCH" between counted and stored frames (518, 383, 346, 437).
- Cost or limitation: none for these clips.
- Say it in the presentation: "I never trust the frame count in the header; I count frames as I read them."
- Likely professor question: What if the counts disagreed? / Answer: The file would still be correct, because the encoder writes the counted number, not the stored one.

### P0-L5: Pad frames to whole 16x16 blocks by copying the edge
- Date / window: Nov 9, Window A (Project decision D5)
- Brief says: frames are split into 16x16 macroblocks (B2); the brief is silent on sizes that do not divide by 16.
- The problem in plain words: 1080 is not a multiple of 16, so the last row of macroblocks would be half empty.
- Options we considered:
  1. Pad with black : good, simple / bad, creates a fake sharp edge that costs bits and confuses motion search.
  2. Pad by repeating the last real row and column (edge replication) : good, no fake edges / bad, none worth noting.
  3. Ignore the partial row : bad, those pixels would be lost.
- What we chose: repeat the edge pixels on the right and bottom only, 1080 becomes 1088. The true size is stored in the file and the decoder crops back.
- Why (plain words): repeating the edge adds no new detail, so it costs almost nothing and does not create false motion.
- Proof: tests `PadTest.hd1080PadsBottomRowsByReplication` and `PadTest.cropUndoesPad`.
- Cost or limitation: the padded rows have no texture, so segmentation treats them as unreliable.
- Say it in the presentation: "1080 is not divisible by 16, so I repeat the last row up to 1088 and crop it off again in the decoder."
- Likely professor question: Why not pad with zeros? / Answer: A black border is a strong edge, and the DCT would spend bits on it. Repeating the edge adds nothing to encode.

### P0-L6: Brightness (Y) from RGB with integer weights
- Date / window: Nov 9, Window A (Project decision D11)
- Brief says: search on one component, Y preferred (B5).
- The problem in plain words: motion search needs one brightness image (Y), computed from red, green and blue.
- Options we considered:
  1. Standard weights 0.299, 0.587, 0.114 in decimal math : good, textbook / bad, slower and can differ in the last bit across machines.
  2. Same weights scaled to whole numbers 77, 150, 29 out of 256 : good, fast, identical everywhere / bad, at most 1 brightness level off.
  3. Use only the green or red channel : good, no math / bad, ignores the brief's preference for Y.
- What we chose: whole-number weights 77, 150, 29. They add up to exactly 256, so gray stays gray and white stays 255.
- Why (plain words): same answer on every computer, which keeps output repeatable, and it is faster.
- Proof: tests `ColorConvTest.grayIsExactForEveryLevelIncludingAbove127` and `withinOneLevelOfFloatFormula` (100,000 random colors).
- Cost or limitation: up to 1 level of 255 different from the decimal formula, invisible for motion search.
- Say it in the presentation: "Y uses the standard BT.601 weights in integer form, so every machine gets exactly the same numbers."
- Likely professor question: Why Y and not R? / Answer: Y carries most of the detail the eye sees and all three colors contribute, so matches are more reliable than on one color.

### P0-L7: How frames are stored in memory (Types.java)
- Date / window: Nov 9 to 10, Window A (interface, approved by Dhruvin Nov 10)
- Brief says: DCT on R, G and B separately (C2); search on Y (B5).
- The problem in plain words: every part of the codec shares one way of holding a frame, so this choice is hard to change later.
- Options we considered:
  1. One array of mixed pixels (R, G, B side by side) : good, compact / bad, every per-channel step must pick colors out.
  2. Three separate planes (all R, then all G, then all B) as whole numbers : good, the DCT and Y work on one plane directly / bad, 4 bytes per value.
  3. Three planes as small numbers (`short`, 2 bytes) : good, half the memory / bad, conversions in every inner loop.
- What we chose: three separate planes of whole numbers 0 to 255; motion vectors and labels as plain arrays over the macroblock grid.
- Why (plain words): Java's `byte` type is signed (it reads 200 as minus 56), so converting once when reading the video and never again removes a classic bug. Memory is fine: about 25 MB per 1080p frame and we only hold a few.
- Proof: tests `VideoIOTest.unpackHandlesBytesAbove127`; approval recorded as P0-D9.
- Cost or limitation: twice the memory of option 3.
- Say it in the presentation: "Frames are three separate color planes, so the DCT and the motion search each read exactly the plane they need."
- Likely professor question: Why not keep bytes? / Answer: Java bytes are signed, so pixel 200 would read as minus 56. Converting once at the input avoids that bug everywhere else.

### P0-L8: Limit n1 and n2 to 0 through 10
- Date / window: Nov 9, Window A
- Brief says: quantization step is 2^n (A2, C3); no range given.
- The problem in plain words: the command line must reject values that make no sense.
- Options we considered:
  1. Accept any whole number : bad, huge n zeroes every block, negative n is meaningless.
  2. Accept 0 to 10 : good, covers every useful step / bad, none.
- What we chose: 0 to 10, with a clear error message otherwise.
- Why (plain words): the largest DCT value an 8x8 block can have is 1024, which is 2^10. A bigger step turns everything into zero.
- Proof: tests `EncoderMainTest.boundaryExponentsAccepted` and `outOfRangeAndNonIntegerExponentsRejected`.
- Cost or limitation: none.
- Say it in the presentation: "n stops at 10 because 2^10 already equals the biggest coefficient an 8x8 block can produce."
- Likely professor question: What does n = 10 look like? / Answer: Almost every coefficient rounds to zero, so each 8x8 block becomes a flat patch of its average color.

### P0-L9: Launcher scripts that pin Java 21
- Date / window: Nov 9, Window A
- Brief says: `myencoder.exe input_video.mp4 n1 n2` and `mydecoder.exe input_video.cmp` (A1, A3).
- The problem in plain words: Java programs are not .exe files, and Dhruvin's Mac defaulted to Java 20, which cannot run our code.
- Options we considered:
  1. Ask the user to type `java -cp ...` : bad, does not match the brief's command.
  2. Small launcher scripts named `myencoder` and `myencoder.bat` that find Java 21 and pass the arguments through unchanged : good, the command looks exactly like the brief / bad, not a real .exe.
  3. Build a real .exe with jpackage : good, literal match / bad, extra build step, Windows only.
- What we chose: launcher scripts; jpackage stays available if the professor insists.
- Why (plain words): the command the grader types is identical to the brief, and it never depends on which Java is the default.
- Proof: `./myencoder data/video2.mp4 2 5` on the Mac printed the validated arguments with Java 21.
- Cost or limitation: the brief literally says .exe; this is in the email to the professor.
- Say it in the presentation: "The commands are exactly the brief's; the scripts just start Java 21 underneath."
- Likely professor question: Where is the .exe? / Answer: Java runs through a launcher with the same name and arguments. I can package a real .exe with jpackage if you need one.

### P0-L10: Measure speed before building the hard parts
- Date / window: Nov 9, Window A
- Brief says: encode plus decode within 10 minutes (E4); play at the original frame rate (D4).
- The problem in plain words: if Java turned out too slow, we would find out after building everything.
- Options we considered:
  1. Build first, optimize later : bad, a late surprise could sink the project.
  2. A one-day speed test first, on real SAL frames : good, numbers before design choices / bad, a day spent on throwaway code.
- What we chose: the speed test (SpeedGate), throwaway code, run on the Mac.
- Why (plain words): it turned guesses into numbers and changed two designs early.
- Proof (Mac, 8 cores): comparing one 16x16 block takes 139.6 ns, so searching every position within 16 pixels would take 1.24 s per 1080p frame (too slow, so we use a faster layered search). Inverse DCT of a whole 1080p frame takes 15.3 ms on all cores. zlib shrinks a frame 10.3 times and unzips it in 15.3 ms. Display stays on time (0.7% late).
- Cost or limitation: one decode step after another would take 37 to 39 ms per 1080p frame, over the 33 ms budget, so the decoder must work on several frames at once.
- Say it in the presentation: "Before writing the codec, I measured every expensive step, and the numbers decided the search method and the decoder design."
- Likely professor question: Why not just use full search? / Answer: At 1.24 s per frame, full search on SAL alone would take 9 minutes. The layered search gets the same vectors in a fraction of that.

### P0-L11: Play on a clock based on the frame number
- Date / window: Nov 9, Window A (Project decision D8)
- Brief says: play at the original frame rate (D4), with pause and step (E2).
- The problem in plain words: each frame must appear exactly 1/30 of a second after the previous one, even though drawing takes a variable amount of time.
- Options we considered:
  1. Sleep 33 ms after each frame : good, simple / bad, drawing time is added every frame, so the video slowly falls behind.
  2. Give frame i a fixed deadline, start time plus i divided by the frame rate : good, delays never pile up / bad, slightly more code.
- What we chose: option 2, in whole nanoseconds using the exact frame rate fraction from the file.
- Why (plain words): a late frame does not push later frames back; the next deadline is already fixed, so the player catches up.
- Proof: video2 played in 12.75 s against 12.77 s; SAL in 14.55 s against 14.57 s (errors 0.1 to 0.2%); tests in `ClockTest`.
- Cost or limitation: after a pause, the clock is restarted from the current frame.
- Say it in the presentation: "Every frame has a deadline computed from its number, so small delays never add up."
- Likely professor question: What happens when one frame is slow? / Answer: It shows a little late, but the next frame's deadline did not move, so playback is back on time right after.

### P0-L12: Shrink 1080p frames ourselves before drawing
- Date / window: Nov 9, Window A (P0-D8)
- Brief says: write your own display code (E1); play at the original rate (D4).
- The problem in plain words: a 1920x1080 frame is bigger than the laptop screen, so it must be shrunk (to 72%). Java's built-in shrinking made SAL play at 20.83 s instead of 14.57 s, with every frame late.
- Options we considered:
  1. Java's built-in scaling : good, one line / bad, measured 48 ms per frame, over budget.
  2. Our own bilinear shrink (each screen pixel is a weighted average of the 4 nearest video pixels), spread over all cores, then a plain copy to the screen : good, measured 1.6 ms per frame / bad, about 100 lines of code.
  3. Nearest pixel only : good, fastest / bad, jagged edges that could be mistaken for compression artifacts.
- What we chose: our own bilinear shrink.
- Why (plain words): it fixed playback (SAL 14.55 s, 0.5 to 1.4% late) and keeps edges smooth.
- Proof: `docs/RESULTS.md` P0 playback table; tests in `DisplayScalerTest`.
- Cost or limitation: shrinking softens fine detail, so judging block artifacts needs a 1:1 zoom (planned for the decoder player).
- Say it in the presentation: "Java's own scaling was too slow for 1080p, so I wrote a simple bilinear scaler that runs in under 2 ms."
- Likely professor question: Why was Java's scaling so slow? / Answer: Because we write pixels straight into the image, Java cannot keep it on the graphics card and redoes the scaling in software every frame. Our scaler does it once, in parallel.
