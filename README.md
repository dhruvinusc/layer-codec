# layer-codec

```
./myencoder input_video.mp4 n1 n2
./mydecoder input_video.cmp
```

Video Layer Based Compression, CSCI 576 (Professor Havaldar), Fall 2026.

The encoder labels every 16x16 macroblock as foreground or background from motion alone,
then quantizes foreground and background 8x8 DCT blocks with different steps (2^n1 and 2^n2).
The decoder reads only the `.cmp` file and plays the result at the original frame rate.

## Usage (exactly as the brief specifies)

```
./myencoder input_video.mp4 n1 n2     # writes input_video.cmp next to the input
./mydecoder input_video.cmp           # decodes and plays
```

n1 is the foreground exponent, n2 the background exponent, both integers 0 to 10.
Windows: `myencoder.bat`, `mydecoder.bat`.

## Requirements

| Tool | Version used | Why |
|---|---|---|
| JDK | Temurin 21 (Apple Silicon) | language level 21; launchers pin it with `/usr/libexec/java_home -v 21` |
| FFmpeg + ffprobe | 7.1 (conda-forge build, on PATH) | mp4 decoding only, called as a separate process |
| Gradle | 9.9.0 via the wrapper (`./gradlew`), no install needed | build and tests |
| git | 2.46.0 | |
| OS | macOS 14.2.1, arm64 | development and demo machine |

If FFmpeg is not on PATH (for example in an IDE run configuration), set `FFMPEG` and
`FFPROBE` to the full paths of the binaries. To force a specific JDK for the launchers,
set `LAYER_CODEC_JAVA` to a `java` executable.

## Build and test

```
./gradlew build        # compiles with -Xlint:all -Werror, runs all tests, builds build/libs/layer-codec.jar
```

## Offline test run

Some sandboxes cannot reach Maven Central. On a machine that can, run `./gradlew vendorJunit`
once; it copies the JUnit console jar to `build-tools/` (git-ignored). Tests can then run with
plain `javac` plus that jar.

## Layout

```
src/main/java/codec/common    frames, video IO (FFmpeg pipe), display, color, padding
src/main/java/codec/encoder   motion estimation, camera motion, segmentation, encoder entry point
src/main/java/codec/decoder   decoder entry point and player
src/main/java/codec/tools     ProbeVideo, VizSeg, SpeedGate
src/test/java/...             JUnit 5 tests
docs/                         SPEC_CHECKLIST, DECISIONS, WORKLOG, RESULTS, img/
presentation/                 notes, slides, demo script, Q&A bank
data/, out/, build/           git-ignored (test clips, generated output, build output)
```
