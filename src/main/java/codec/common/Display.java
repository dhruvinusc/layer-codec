package codec.common;

import codec.common.Types.Frame;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * Our own video window (brief: write your own display code). The only class that touches Swing.
 *
 * <p>One reused TYPE_INT_RGB image, written through its int raster (no per-pixel setRGB calls).
 * {@link #show} returns only after the frame is painted, so the caller's clock measures what the
 * viewer actually sees.
 *
 * <p>Frames larger than the screen are scaled down by our own integer bilinear {@link Scaler},
 * run on all cores, and then drawn 1:1. Letting Java2D scale an image whose raster we write
 * directly measured about 48 ms per 1080p frame on the Mac (Nov 9), over the 33 ms budget,
 * because such an image cannot be cached on the GPU and is rescaled in software every time.
 */
public final class Display implements AutoCloseable {

    /** Player commands. Space play/pause, Right step, Left step back, Q or Esc or closing the window quits. */
    public enum Key { TOGGLE_PLAY, STEP_FORWARD, STEP_BACK, QUIT }

    private final int width;
    private final int height;
    private final double scale;
    private final BlockingQueue<Key> keys = new LinkedBlockingQueue<>();
    private final Canvas canvas;
    private JFrame window;
    private long lastLoadNanos;
    private long lastPaintNanos;

    public Display(String title, int width, int height) {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("no screen available (headless JVM); run this on the laptop");
        }
        this.width = width;
        this.height = height;
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        this.scale = Math.min(1.0, Math.min(screen.width * 0.95 / width, screen.height * 0.90 / height));
        int dw = Math.max(1, (int) Math.round(width * scale));
        int dh = Math.max(1, (int) Math.round(height * scale));
        this.canvas = new Canvas(new Scaler(width, height, dw, dh));
        onEdt(() -> buildWindow(title));
    }

    private void buildWindow(String title) {
        window = new JFrame(title);
        window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        window.add(canvas);
        window.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                Key k = switch (e.getKeyCode()) {
                    case KeyEvent.VK_SPACE -> Key.TOGGLE_PLAY;
                    case KeyEvent.VK_RIGHT -> Key.STEP_FORWARD;
                    case KeyEvent.VK_LEFT -> Key.STEP_BACK;
                    case KeyEvent.VK_Q, KeyEvent.VK_ESCAPE -> Key.QUIT;
                    default -> null;
                };
                if (k != null) {
                    keys.add(k);
                }
            }
        });
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                keys.add(Key.QUIT);
            }
        });
        window.pack();
        window.setLocationRelativeTo(null);
        window.setVisible(true);
        window.requestFocus();
    }

    public double scale() {
        return scale;
    }

    /** Copies {@code f} into the window image and blocks until it is painted. */
    public void show(Frame f) {
        if (f.width() != width || f.height() != height) {
            throw new IllegalArgumentException("frame size does not match the window");
        }
        long t0 = System.nanoTime();
        canvas.load(f);
        long t1 = System.nanoTime();
        onEdt(() -> canvas.paintImmediately(0, 0, canvas.getWidth(), canvas.getHeight()));
        Toolkit.getDefaultToolkit().sync();
        lastLoadNanos = t1 - t0;
        lastPaintNanos = System.nanoTime() - t1;
    }

    /**
     * Shows a frame already packed as 0xRRGGBB (the decoder's cache format, P3.2) and blocks until
     * it is painted. Same scaler and paint path as {@link #show(Frame)}.
     */
    public void show(int[] rgb, int w, int h) {
        if (w != width || h != height || rgb.length != w * h) {
            throw new IllegalArgumentException("frame size does not match the window");
        }
        long t0 = System.nanoTime();
        canvas.loadPacked(rgb);
        long t1 = System.nanoTime();
        onEdt(() -> canvas.paintImmediately(0, 0, canvas.getWidth(), canvas.getHeight()));
        Toolkit.getDefaultToolkit().sync();
        lastLoadNanos = t1 - t0;
        lastPaintNanos = System.nanoTime() - t1;
    }

    /** One line of text drawn over the top-left corner of the picture (null: none). */
    public void setStatus(String text) {
        canvas.status = text;
    }

    /** Time the last {@link #show} spent packing (and scaling) pixels. */
    public long lastLoadNanos() {
        return lastLoadNanos;
    }

    /** Time the last {@link #show} spent painting to the screen. */
    public long lastPaintNanos() {
        return lastPaintNanos;
    }

    public void setTitle(String title) {
        SwingUtilities.invokeLater(() -> window.setTitle(title));
    }

    /** Next key press, or null if none is waiting. */
    public Key pollKey() {
        return keys.poll();
    }

    /** Waits up to {@code nanos} for a key press; null on timeout. */
    public Key waitKey(long nanos) {
        try {
            return keys.poll(nanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Key.QUIT;
        }
    }

    @Override
    public void close() {
        SwingUtilities.invokeLater(() -> window.dispose());
    }

    private static void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("display error", e.getCause());
        }
    }

    /**
     * Integer bilinear resampler from a w x h frame to dw x dh packed RGB.
     *
     * <p>Source positions and 8-bit weights are precomputed per column and per row, so the inner
     * loop is four multiplies per channel. Pixel centers are aligned ((x + 0.5) / scale - 0.5),
     * so the picture does not shift. When dw == w and dh == h every weight is 0 and the output
     * equals the input exactly. Rows are independent, so they run in parallel.
     */
    static final class Scaler {
        final int w;
        final int h;
        final int dw;
        final int dh;
        private final int[] x0;
        private final int[] x1;
        private final int[] wx;
        private final int[] y0;
        private final int[] y1;
        private final int[] wy;

        Scaler(int w, int h, int dw, int dh) {
            this.w = w;
            this.h = h;
            this.dw = dw;
            this.dh = dh;
            x0 = new int[dw];
            x1 = new int[dw];
            wx = new int[dw];
            y0 = new int[dh];
            y1 = new int[dh];
            wy = new int[dh];
            taps(w, dw, x0, x1, wx);
            taps(h, dh, y0, y1, wy);
        }

        private static void taps(int src, int dst, int[] i0, int[] i1, int[] wt) {
            for (int d = 0; d < dst; d++) {
                double s = (d + 0.5) * src / dst - 0.5;
                s = Math.max(0, Math.min(src - 1, s));
                int a = (int) Math.floor(s);
                i0[d] = a;
                i1[d] = Math.min(a + 1, src - 1);
                wt[d] = (int) Math.round((s - a) * 256);
            }
        }

        void apply(Frame f, int[] out) {
            int[] r = f.r();
            int[] g = f.g();
            int[] b = f.b();
            if (dw == w && dh == h) {
                for (int i = 0; i < out.length; i++) {
                    out[i] = (r[i] << 16) | (g[i] << 8) | b[i];
                }
                return;
            }
            IntStream.range(0, dh).parallel().forEach(y -> row(r, g, b, out, y));
        }

        private void row(int[] r, int[] g, int[] b, int[] out, int y) {
            int top = y0[y] * w;
            int bot = y1[y] * w;
            int vy = wy[y];
            int uy = 256 - vy;
            int o = y * dw;
            for (int x = 0; x < dw; x++) {
                int a = top + x0[x];
                int c = top + x1[x];
                int d = bot + x0[x];
                int e = bot + x1[x];
                int vx = wx[x];
                int ux = 256 - vx;
                out[o + x] = (mix(r, a, c, d, e, ux, vx, uy, vy) << 16)
                        | (mix(g, a, c, d, e, ux, vx, uy, vy) << 8)
                        | mix(b, a, c, d, e, ux, vx, uy, vy);
            }
        }

        /** Same resampling as {@link #apply}, from packed 0xRRGGBB input (bit-identical, tested). */
        void applyPacked(int[] src, int[] out) {
            if (dw == w && dh == h) {
                System.arraycopy(src, 0, out, 0, out.length);
                return;
            }
            IntStream.range(0, dh).parallel().forEach(y -> rowPacked(src, out, y));
        }

        private void rowPacked(int[] src, int[] out, int y) {
            int top = y0[y] * w;
            int bot = y1[y] * w;
            int vy = wy[y];
            int uy = 256 - vy;
            int o = y * dw;
            for (int x = 0; x < dw; x++) {
                int a = src[top + x0[x]];
                int c = src[top + x1[x]];
                int d = src[bot + x0[x]];
                int e = src[bot + x1[x]];
                int vx = wx[x];
                int ux = 256 - vx;
                out[o + x] = (mixPacked(a, c, d, e, 16, ux, vx, uy, vy) << 16)
                        | (mixPacked(a, c, d, e, 8, ux, vx, uy, vy) << 8)
                        | mixPacked(a, c, d, e, 0, ux, vx, uy, vy);
            }
        }

        private static int mixPacked(int a, int c, int d, int e, int shift, int ux, int vx, int uy, int vy) {
            int t = ((a >>> shift) & 0xFF) * ux + ((c >>> shift) & 0xFF) * vx;
            int u = ((d >>> shift) & 0xFF) * ux + ((e >>> shift) & 0xFF) * vx;
            return (t * uy + u * vy + 32768) >> 16;
        }

        private static int mix(int[] p, int a, int c, int d, int e, int ux, int vx, int uy, int vy) {
            int t = p[a] * ux + p[c] * vx;
            int u = p[d] * ux + p[e] * vx;
            return (t * uy + u * vy + 32768) >> 16;
        }
    }

    /** Draws the display-sized image 1:1. The lock keeps a half-written frame from being drawn. */
    private static final class Canvas extends JPanel {
        private static final long serialVersionUID = 1L;
        private final transient Scaler scaler;
        private final transient BufferedImage image;
        private final transient int[] pixels;
        private final transient Object lock = new Object();
        /** Status line, written by the player thread, read on the event thread. */
        transient volatile String status;

        Canvas(Scaler scaler) {
            this.scaler = scaler;
            this.image = new BufferedImage(scaler.dw, scaler.dh, BufferedImage.TYPE_INT_RGB);
            this.pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            setPreferredSize(new Dimension(scaler.dw, scaler.dh));
            setDoubleBuffered(true);
        }

        void load(Frame f) {
            synchronized (lock) {
                scaler.apply(f, pixels);
            }
        }

        void loadPacked(int[] rgb) {
            synchronized (lock) {
                scaler.applyPacked(rgb, pixels);
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            synchronized (lock) {
                g.drawImage(image, 0, 0, null);
            }
            String s = status;
            if (s != null) {
                drawStatus(g, s);
            }
        }

        /** White text on a translucent black bar, so it reads over any picture. */
        private static void drawStatus(Graphics g, String s) {
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
            FontMetrics fm = g.getFontMetrics();
            int pad = 6;
            g.setColor(new Color(0, 0, 0, 170));
            g.fillRect(8, 8, fm.stringWidth(s) + 2 * pad, fm.getHeight() + pad);
            g.setColor(Color.WHITE);
            g.drawString(s, 8 + pad, 8 + pad / 2 + fm.getAscent());
        }
    }
}
