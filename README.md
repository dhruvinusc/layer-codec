# layer-codec

Video Layer Based Compression, CSCI 576 (Professor Havaldar), Fall 2026.

```
./myencoder input_video.mp4 n1 n2     # writes input_video.cmp next to the input
./mydecoder input_video.cmp           # decodes and plays it
```

The encoder works out which parts of each frame are moving objects, using motion alone.
It splits every frame into 16x16 macroblocks, finds a motion vector for each one against the
previous frame, removes the motion caused by the camera, and labels the blocks that still move
together as foreground. Each 8x8 block of R, G and B then goes through a DCT, and its
coefficients are divided by 2^n1 (foreground) or 2^n2 (background) and rounded. That rounding
is the only place information is lost.

The decoder needs nothing but the `.cmp` file. It undoes the quantization, runs the inverse DCT
and plays the video at its original frame rate.

## Usage

n1 is the foreground exponent and n2 the background exponent, both integers from 0 to 10.
A smaller n keeps more detail. With `n1 < n2` the moving subject stays sharp while the
background gets blocky; swap them and the effect reverses.

On Windows, use `myencoder.bat` and `mydecoder.bat`.

To compare against the original, pass it as a second argument:

```
./mydecoder input_video.cmp input_video.mp4
```

Player keys: space play/pause, right arrow step, left arrow step back, `s` side by side
(needs the original), `z` 1:1 zoom (drag to pan), `d` foreground overlay, `q` quit.

## Extra credit

Both modes are optional and off by default, so the baseline output never changes.

- `./myencoder input_video.mp4 n1 n2 --mode rle` writes mode 1: coefficients in zigzag order
  with run-length coding. Same pixels as the baseline, 29 to 40% smaller.
- `codec.tools.McEncode` writes mode 2: each frame is predicted from the previous decoded frame
  using the motion vectors, and only the difference is coded (an I frame every 2 frames).
  36 to 51% smaller, within 0.24 dB of the baseline.

`mydecoder` plays all three modes; the mode is stored in the file header.

## Requirements

| Tool | Version used | Why |
|---|---|---|
| JDK | Temurin 21 (Apple Silicon) | language level 21; the launchers find it with `/usr/libexec/java_home -v 21` |
| FFmpeg + ffprobe | 7.1 (conda-forge build, on PATH) | reads mp4 frames only, run as a separate process |
| Gradle | 9.9.0 through the wrapper (`./gradlew`), nothing to install | build and tests |
| git | 2.46.0 | |
| OS | macOS 14.2.1, arm64 | development and demo machine |

Everything else (motion search, camera motion, segmentation, DCT, quantization, the file
format, playback) is written by hand in plain Java.

If FFmpeg is not on PATH (for example inside an IDE), set `FFMPEG` and `FFPROBE` to the full
paths of the two programs. To make the launchers use a specific JDK, set `LAYER_CODEC_JAVA` to
a `java` executable.

## Build and test

```
./gradlew build        # compiles with -Xlint:all -Werror, runs all 234 tests, builds build/libs/layer-codec.jar
```

If a machine cannot reach Maven Central, run `./gradlew vendorJunit` once on a machine that can.
It copies the JUnit console jar into `build-tools/` (ignored by git), and the tests can then be
run with plain `javac` and that jar.

## Layout

```
src/main/java/codec/common    shared pieces: frames, video reading, display, padding, DCT,
                              quantization, the .cmp format, zlib, frame encoder, modes 1 and 2
src/main/java/codec/encoder   motion estimation, camera motion, segmentation, myencoder entry point
src/main/java/codec/decoder   frame decoding, parallel decode, frame cache, player, mydecoder entry point
src/main/java/codec/tools     CmpDump, VizSeg, Psnr, McEncode, CodecHarness, ProbeVideo, SpeedGate, CmpRecode
src/test/java/codec           JUnit 5 tests
docs/                         requirement checklist, decisions, results, report draft, images
demo/                         live demo menu and notes
presentation/                 talk notes and slides
data/, out/, build/           ignored by git (test clips, generated output, build output)
```

`MAP.md` lists every folder and key file with a one-line "open this when you want to..." note.
