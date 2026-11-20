package codec.common;

import codec.common.Types.Frame;
import codec.common.Types.VideoInfo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Reads mp4 frames through an external FFmpeg process (03 D1, D11). The only class that runs FFmpeg.
 *
 * <p>ffprobe gives size, frame rate and rotation. ffmpeg decodes with auto-rotation OFF and an
 * explicit rotation filter, and writes raw rgb24 to a pipe. Frames are streamed one at a time.
 * Set FFMPEG / FFPROBE to override the binaries found on PATH.
 */
public final class VideoIO {

    private VideoIO() {
    }

    static String ffmpegBin() {
        return envOr("FFMPEG", "ffmpeg");
    }

    static String ffprobeBin() {
        return envOr("FFPROBE", "ffprobe");
    }

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    /** Runs ffprobe on the first video stream and returns upright clip facts. */
    public static VideoInfo probe(Path clip) throws IOException {
        List<String> cmd = List.of(ffprobeBin(), "-v", "error", "-select_streams", "v:0",
                "-show_streams", "-of", "default=noprint_wrappers=1", clip.toString());
        return parseProbe(runToString(cmd));
    }

    /** Parses ffprobe key=value output. Package-private for tests. */
    static VideoInfo parseProbe(String text) {
        Map<String, String> kv = new HashMap<>();
        for (String line : text.split("\\R")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                kv.putIfAbsent(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        int storedW = requireInt(kv, "width");
        int storedH = requireInt(kv, "height");
        int[] fps = parseRational(kv.get("r_frame_rate"));
        int frames = parseIntOr(kv.get("nb_frames"), -1);
        int rot = rotationCw(kv.get("rotation"), kv.get("TAG:rotate"));
        boolean swap = rot == 90 || rot == 270;
        int w = swap ? storedH : storedW;
        int h = swap ? storedW : storedH;
        return new VideoInfo(w, h, Types.paddedSize(w), Types.paddedSize(h), fps[0], fps[1], frames, rot);
    }

    /**
     * Clockwise degrees to apply so the frame is upright.
     *
     * <p>The display-matrix side data "rotation" is counter-clockwise (FFmpeg 5 and later keep only
     * this). The legacy stream tag "rotate" is clockwise (FFmpeg 4). Side data wins when present.
     */
    static int rotationCw(String sideDataCcw, String legacyTagCw) {
        double cw;
        if (sideDataCcw != null && !sideDataCcw.isBlank()) {
            cw = -Double.parseDouble(sideDataCcw);
        } else if (legacyTagCw != null && !legacyTagCw.isBlank()) {
            cw = Double.parseDouble(legacyTagCw);
        } else {
            return 0;
        }
        int deg = (int) Math.round(cw);
        deg = ((deg % 360) + 360) % 360;
        if (deg % 90 != 0) {
            throw new IllegalArgumentException("unsupported rotation: " + cw + " degrees");
        }
        return deg;
    }

    /** FFmpeg filter that rotates a stored frame clockwise by {@code cw} degrees; empty for 0. */
    static String rotationFilter(int cw) {
        return switch (cw) {
            case 0 -> "";
            case 90 -> "transpose=clock";
            case 180 -> "hflip,vflip";
            case 270 -> "transpose=cclock";
            default -> throw new IllegalArgumentException("rotation must be 0, 90, 180 or 270: " + cw);
        };
    }

    /** Opens a streaming reader. Close it (try-with-resources) to stop the FFmpeg process. */
    public static Reader open(Path clip) throws IOException {
        return new Reader(clip, probe(clip));
    }

    /** rgb24 interleaved bytes to three 0..255 int planes. The single place {@code & 0xFF} matters. */
    static void unpackRgb24(byte[] src, int[] r, int[] g, int[] b) {
        for (int i = 0, j = 0; i < r.length; i++, j += 3) {
            r[i] = src[j] & 0xFF;
            g[i] = src[j + 1] & 0xFF;
            b[i] = src[j + 2] & 0xFF;
        }
    }

    /** Streams upright frames from one clip. Not thread-safe. */
    public static final class Reader implements AutoCloseable {
        private final VideoInfo info;
        private final Process process;
        private final InputStream stdout;
        private final StreamDrain stderr;
        private final byte[] buffer;
        private int framesRead;

        Reader(Path clip, VideoInfo info) throws IOException {
            this.info = info;
            List<String> cmd = new ArrayList<>(List.of(ffmpegBin(), "-nostdin", "-v", "error",
                    "-noautorotate", "-i", clip.toString(), "-map", "0:v:0", "-an", "-sn"));
            String filter = rotationFilter(info.rotationCw());
            if (!filter.isEmpty()) {
                cmd.add("-vf");
                cmd.add(filter);
            }
            cmd.addAll(List.of("-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1"));
            this.process = start(cmd);
            this.stdout = process.getInputStream();
            this.stderr = new StreamDrain(process.getErrorStream());
            this.buffer = new byte[info.width() * info.height() * 3];
        }

        public VideoInfo info() {
            return info;
        }

        public int framesRead() {
            return framesRead;
        }

        /** Reads the next frame into {@code dst}. Returns false at end of stream. */
        public boolean read(Frame dst) throws IOException {
            if (dst.width() != info.width() || dst.height() != info.height()) {
                throw new IllegalArgumentException("frame is " + dst.width() + "x" + dst.height()
                        + ", clip is " + info.width() + "x" + info.height());
            }
            int n = stdout.readNBytes(buffer, 0, buffer.length);
            if (n == 0) {
                checkExit();
                return false;
            }
            if (n < buffer.length) {
                throw new IOException("truncated frame " + framesRead + ": " + n + " of "
                        + buffer.length + " bytes. ffmpeg said: " + stderr.tail());
            }
            unpackRgb24(buffer, dst.r(), dst.g(), dst.b());
            framesRead++;
            return true;
        }

        /** Convenience: allocates a new frame, or returns null at end of stream. */
        public Frame read() throws IOException {
            Frame f = Frame.allocate(info.width(), info.height());
            return read(f) ? f : null;
        }

        private void checkExit() throws IOException {
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    throw new IOException("ffmpeg did not exit after end of stream");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted waiting for ffmpeg", e);
            }
            if (process.exitValue() != 0) {
                throw new IOException("ffmpeg exited " + process.exitValue() + ": " + stderr.tail());
            }
        }

        @Override
        public void close() {
            process.destroy();
            try {
                stdout.close();
            } catch (IOException ignored) {
                // The process is being torn down; nothing useful to report.
            }
            stderr.join();
        }
    }

    private static Process start(List<String> cmd) throws IOException {
        try {
            return new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            throw new IOException("cannot run '" + cmd.get(0) + "'. Install FFmpeg or set "
                    + (cmd.get(0).contains("probe") ? "FFPROBE" : "FFMPEG") + " to its full path", e);
        }
    }

    private static String runToString(List<String> cmd) throws IOException {
        Process p = start(cmd);
        StreamDrain err = new StreamDrain(p.getErrorStream());
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            int code = p.waitFor();
            err.join();
            if (code != 0) {
                throw new IOException(cmd.get(0) + " exited " + code + ": " + err.tail());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for " + cmd.get(0), e);
        }
        return out;
    }

    /** Reads a stream on a daemon thread so a full stderr pipe can never block the child process. */
    private static final class StreamDrain {
        private static final int TAIL_BYTES = 4096;
        private final ByteArrayOutputStream tail = new ByteArrayOutputStream();
        private final Thread thread;

        StreamDrain(InputStream in) {
            thread = new Thread(() -> {
                byte[] chunk = new byte[1024];
                try (in) {
                    int n;
                    while ((n = in.read(chunk)) > 0) {
                        synchronized (tail) {
                            tail.write(chunk, 0, n);
                            if (tail.size() > TAIL_BYTES * 2) {
                                byte[] keep = tail.toByteArray();
                                tail.reset();
                                tail.write(keep, keep.length - TAIL_BYTES, TAIL_BYTES);
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // Stream closed because the process ended; the tail so far is enough.
                }
            }, "ffmpeg-stderr");
            thread.setDaemon(true);
            thread.start();
        }

        String tail() {
            synchronized (tail) {
                return tail.toString(StandardCharsets.UTF_8).trim();
            }
        }

        void join() {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static int requireInt(Map<String, String> kv, String key) {
        String v = kv.get(key);
        if (v == null) {
            throw new IllegalArgumentException("ffprobe output has no '" + key + "'");
        }
        return Integer.parseInt(v);
    }

    private static int parseIntOr(String v, int fallback) {
        try {
            return v == null ? fallback : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int[] parseRational(String v) {
        if (v == null || !v.contains("/")) {
            throw new IllegalArgumentException("bad frame rate: " + v);
        }
        String[] p = v.split("/");
        int num = Integer.parseInt(p[0]);
        int den = Integer.parseInt(p[1]);
        if (num <= 0 || den <= 0) {
            throw new IllegalArgumentException("bad frame rate: " + v);
        }
        return new int[] {num, den};
    }
}
