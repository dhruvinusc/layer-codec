package codec.common;

import codec.common.Types.Frame;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
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
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * Our own video window (brief: write your own display code). The only class that touches Swing.
 *
 * <p>One reused TYPE_INT_RGB image, written through its int raster (no per-pixel setRGB calls).
 * {@link #show} returns only after the frame is painted, so the caller's clock measures what the
 * viewer actually sees. Frames larger than the screen are scaled down to fit (1080p on a laptop).
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

    public Display(String title, int width, int height) {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("no screen available (headless JVM); run this on the laptop");
        }
        this.width = width;
        this.height = height;
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        this.scale = Math.min(1.0, Math.min(screen.width * 0.95 / width, screen.height * 0.90 / height));
        this.canvas = new Canvas(width, height, scale);
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
        canvas.load(f);
        onEdt(() -> canvas.paintImmediately(0, 0, canvas.getWidth(), canvas.getHeight()));
        Toolkit.getDefaultToolkit().sync();
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

    /** Paints the shared image, scaled. The lock keeps a half-copied frame from ever being drawn. */
    private static final class Canvas extends JPanel {
        private static final long serialVersionUID = 1L;
        private final transient BufferedImage image;
        private final transient int[] pixels;
        private final transient Object lock = new Object();
        private final double scale;

        Canvas(int w, int h, double scale) {
            this.image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            this.pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            this.scale = scale;
            setPreferredSize(new Dimension((int) Math.round(w * scale), (int) Math.round(h * scale)));
            setDoubleBuffered(true);
        }

        void load(Frame f) {
            int[] r = f.r();
            int[] g = f.g();
            int[] b = f.b();
            synchronized (lock) {
                for (int i = 0; i < pixels.length; i++) {
                    pixels[i] = (r[i] << 16) | (g[i] << 8) | b[i];
                }
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g;
            if (scale < 1.0) {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            }
            synchronized (lock) {
                g2.drawImage(image, 0, 0, getWidth(), getHeight(), null);
            }
        }
    }
}
