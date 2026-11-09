package codec.tools;

import codec.common.Types.Frame;
import codec.common.Types.VideoInfo;
import codec.common.VideoIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * {@code ProbeVideo <clip>}: prints VideoInfo, saves an upright PNG of frame 0 to out/evidence/,
 * then counts frames by reading to the end of the stream (03 D11: never trust the tag).
 */
public final class ProbeVideo {

    private ProbeVideo() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: ProbeVideo <clip.mp4>");
            System.exit(2);
        }
        Path clip = Path.of(args[0]);
        String name = clip.getFileName().toString().replaceFirst("\\.[^.]+$", "");
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
