package codec.tools;

import codec.common.Clock;
import codec.common.Display;
import codec.common.Types.Frame;
import codec.common.Types.VideoInfo;
import codec.common.VideoIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.LockSupport;
import javax.imageio.ImageIO;

/**
 * {@code ProbeVideo <clip>}: prints VideoInfo, saves an upright PNG of frame 0 to out/evidence/,
 * then counts frames by reading to the end of the stream (03 D11: never trust the tag).
 *
 * <p>{@code ProbeVideo <clip> --play}: plays the input on the steady clock with
 * play/pause/step, and reports measured play time and late frames (P0.7 evidence).
 */
public final class ProbeVideo {

    /** How far back Left can step. 30 frames = 1 s; 1080p costs 25 MB per frame. */
    static final int RING = 30;

    private ProbeVideo() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2 || (args.length == 2 && !args[1].equals("--play"))) {
            System.err.println("usage: ProbeVideo <clip.mp4> [--play]");
            System.exit(2);
        }
        Path clip = Path.of(args[0]);
        if (args.length == 2) {
            play(clip);
        } else {
            probe(clip);
        }
        System.exit(0); // Swing threads would otherwise keep the JVM alive.
    }

    static void probe(Path clip) throws IOException {
        String name = stem(clip);
        Path outDir = Path.of("out", "evidence");
        Files.createDirectories(outDir);
        try (VideoIO.Reader reader = VideoIO.open(clip)) {
            VideoInfo info = reader.info();
            System.out.println(name + ": " + info);
            long t0 = System.nanoTime();
            Frame f = Frame.allocate(info.width(), info.height());
            if (!reader.read(f)) {
                throw new IOException("clip has no frames");
            }
            Path png = outDir.resolve(name + "_frame0.png");
            ImageIO.write(toImage(f), "png", png.toFile());
            while (reader.read(f)) {
                // Counting only; the frame buffer is reused.
            }
            double sec = (System.nanoTime() - t0) / 1e9;
            int counted = reader.framesRead();
            System.out.printf("  upright %dx%d, padded %dx%d, fps %d/%d (%.3f), rotationCw %d%n",
                    info.width(), info.height(), info.paddedW(), info.paddedH(),
                    info.fpsNum(), info.fpsDen(), info.fps(), info.rotationCw());
            System.out.printf("  frames counted %d (tag %d) %s, duration %.2f s, read at %.1f fps%n",
                    counted, info.frameCount(), counted == info.frameCount() ? "MATCH" : "DIFFERS",
                    counted / info.fps(), counted / sec);
            System.out.println("  thumbnail " + png);
        }
    }

    /** Last RING decoded frames, addressed by frame index. Frames are decoded straight into a slot. */
    private static final class FrameRing {
        private final Frame[] slots = new Frame[RING];
        private int newest = -1;

        FrameRing(int w, int h) {
            for (int i = 0; i < RING; i++) {
                slots[i] = Frame.allocate(w, h);
            }
        }

        boolean has(int index) {
            return index >= 0 && index <= newest && index > newest - RING;
        }

        Frame get(int index) {
            return slots[index % RING];
        }

        /** Makes {@code index} available, decoding one new frame if needed. False at end of stream. */
        boolean ensure(int index, VideoIO.Reader reader) throws IOException {
            if (has(index)) {
                return true;
            }
            if (index != newest + 1) {
                return false;
            }
            if (!reader.read(slots[index % RING])) {
                return false;
            }
            newest = index;
            return true;
        }
    }

    static void play(Path clip) throws IOException {
        try (VideoIO.Reader reader = VideoIO.open(clip)) {
            VideoInfo info = reader.info();
            String name = stem(clip);
            Display display = new Display(name, info.width(), info.height());
            System.out.printf("%s: %dx%d at %d/%d fps, window scale %.2f%n", name, info.width(),
                    info.height(), info.fpsNum(), info.fpsDen(), display.scale());
            System.out.println("keys: space play/pause, right step, left step back, q quit");
            try {
                new PlayLoop(reader, display, info, name).run();
            } finally {
                display.close();
            }
        }
    }

    /** The playback state machine. Timing is honest only for an uninterrupted first pass. */
    private static final class PlayLoop {
        private final VideoIO.Reader reader;
        private final Display display;
        private final VideoInfo info;
        private final String name;
        private final Clock clock;
        private final FrameRing ring;
        private int shown = -1;
        private boolean playing = true;
        private boolean interrupted;
        private boolean reported;
        private long firstShowNanos;
        private int late;

        PlayLoop(VideoIO.Reader reader, Display display, VideoInfo info, String name) {
            this.reader = reader;
            this.display = display;
            this.info = info;
            this.name = name;
            this.clock = new Clock(info.fpsNum(), info.fpsDen());
            this.ring = new FrameRing(info.width(), info.height());
        }

        void run() throws IOException {
            clock.start(0);
            while (true) {
                Display.Key k = playing ? display.pollKey() : display.waitKey(50_000_000L);
                if (k == Display.Key.QUIT) {
                    return;
                }
                if (k != null) {
                    handle(k);
                }
                if (playing) {
                    advance();
                }
            }
        }

        private void handle(Display.Key k) throws IOException {
            switch (k) {
                case TOGGLE_PLAY -> {
                    if (playing) {
                        pause();
                    } else if (ring.ensure(shown + 1, reader)) {
                        playing = true;
                        clock.start(shown + 1);
                    }
                }
                case STEP_FORWARD -> {
                    pause();
                    if (ring.ensure(shown + 1, reader)) {
                        showFrame(shown + 1);
                    }
                }
                case STEP_BACK -> {
                    pause();
                    if (ring.has(shown - 1)) {
                        showFrame(shown - 1);
                    }
                }
                default -> {
                }
            }
        }

        private void pause() {
            if (playing) {
                playing = false;
                interrupted = true;
                title();
            }
        }

        /** Decode ahead, wait for the deadline, then show. Re-checks keys at least every 5 ms. */
        private void advance() throws IOException {
            int next = shown + 1;
            if (!ring.ensure(next, reader)) {
                playing = false;
                report();
                title();
                return;
            }
            long wait = clock.nanosUntil(next);
            if (wait > 0) {
                LockSupport.parkNanos(Math.min(wait, 5_000_000L));
                return;
            }
            if (-wait > clock.periodNanos() / 2) {
                late++;
            }
            showFrame(next);
        }

        private void showFrame(int index) {
            display.show(ring.get(index));
            if (index == 0) {
                firstShowNanos = clock.nowNanos();
            }
            shown = index;
            if (!playing || index % 15 == 0) {
                title();
            }
        }

        private void title() {
            display.setTitle(String.format("%s  frame %d  %s", name, shown, playing ? "PLAYING" : "PAUSED"));
        }

        private void report() {
            if (reported) {
                return;
            }
            reported = true;
            int frames = shown + 1;
            double expected = frames / info.fps();
            if (interrupted) {
                System.out.printf("end of clip: %d frames (paused or stepped, timing not measured)%n", frames);
                return;
            }
            double measured = (clock.nowNanos() - firstShowNanos + clock.periodNanos()) / 1e9;
            System.out.printf("end of clip: %d frames played in %.2f s (clip %.2f s, error %+.1f%%), "
                    + "late frames %d (%.1f%%)%n", frames, measured, expected,
                    100.0 * (measured - expected) / expected, late, 100.0 * late / frames);
            System.out.println("still open: left/right to step, q to quit");
        }
    }

    static String stem(Path clip) {
        return clip.getFileName().toString().replaceFirst("\\.[^.]+$", "");
    }

    static BufferedImage toImage(Frame f) {
        BufferedImage img = new BufferedImage(f.width(), f.height(), BufferedImage.TYPE_INT_RGB);
        int[] rgb = new int[f.width() * f.height()];
        for (int i = 0; i < rgb.length; i++) {
            rgb[i] = (f.r()[i] << 16) | (f.g()[i] << 8) | f.b()[i];
        }
        img.setRGB(0, 0, f.width(), f.height(), rgb, 0, f.width());
        return img;
    }
}
