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
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.KeyStroke;
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
        MouseAdapter drag = new MouseAdapter() {
            private int lastX;
            private int lastY;

            @Override
            public void mousePressed(MouseEvent e) {
                lastX = e.getX();
                lastY = e.getY();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                canvas.pan(lastX - e.getX(), lastY - e.getY()); // the picture follows the mouse
                lastX = e.getX();
                lastY = e.getY();
            }
        };
        canvas.addMouseListener(drag);
        canvas.addMouseMotionListener(drag);
        // Keys are bound on the root pane for the whole window (P3-L16), not as a KeyListener on
        // the frame: a frame listener only hears keys while the frame itself owns the focus, so
        // on the Mac a click or a focus hand-off could leave some keys dead. Shift and Caps Lock
        // variants are bound too.
        canvas.setFocusable(false);
        JComponent root = window.getRootPane();
        InputMap in = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap act = root.getActionMap();
        bind(in, act, "play", () -> keys.add(Key.TOGGLE_PLAY), KeyEvent.VK_SPACE);
        bind(in, act, "right", () -> keys.add(Key.STEP_FORWARD), KeyEvent.VK_RIGHT);
        bind(in, act, "left", () -> keys.add(Key.STEP_BACK), KeyEvent.VK_LEFT);
        bind(in, act, "quit", () -> keys.add(Key.QUIT), KeyEvent.VK_Q, KeyEvent.VK_ESCAPE);
        // View keys change only how the current picture is drawn, so the display handles them
        // itself, right here on the event thread (P3.4, P3.5): s side by side, z 1:1, d overlay.
        bind(in, act, "side", () -> viewKey('s', View.Toggle.SIDE_BY_SIDE), KeyEvent.VK_S);
        bind(in, act, "zoom", () -> viewKey('z', View.Toggle.ZOOM), KeyEvent.VK_Z);
        bind(in, act, "overlay", () -> viewKey('d', View.Toggle.OVERLAY), KeyEvent.VK_D);
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
        showLayers(rgb, null, 0, null, w, h);
    }

    private static void bind(InputMap in, ActionMap act, String name, Runnable r, int... codes) {
        for (int c : codes) {
            in.put(KeyStroke.getKeyStroke(c, 0), name);
            in.put(KeyStroke.getKeyStroke(c, InputEvent.SHIFT_DOWN_MASK), name);
        }
        act.put(name, new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }

    /** Event thread: toggles a view and says so in the terminal, so a key press is never silent. */
    private void viewKey(char key, View.Toggle t) {
        String line = canvas.toggle(t);
        System.out.println("view     : " + key + " -> " + line);
    }

    /**
     * Shows a decoded frame (packed 0xRRGGBB, w x h) with, optionally, its 8x8 block types for
     * the overlay ({@code types} with {@code blocksX} blocks per row, or null) and the original
     * frame for side by side ({@code right}, or null). How they are drawn (fit, side by side,
     * 1:1 zoom, overlay) is the view state the viewer toggles with s, z, d and mouse drag.
     * The arrays are read again when a view key is pressed, so the caller must not reuse them
     * until the next call (the decoder's 30-frame rings guarantee that).
     */
    public void showLayers(int[] left, byte[] types, int blocksX, int[] right, int w, int h) {
        if (left.length != w * h || (right != null && right.length != w * h)) {
            throw new IllegalArgumentException("frame arrays do not match " + w + "x" + h);
        }
        long t0 = System.nanoTime();
        canvas.loadLayers(left, types, blocksX, right, w, h);
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
            applyPackedInto(src, out, dw, 0, 0);
        }

        /** Resamples into the dw x dh rectangle at (ox, oy) of an image {@code stride} pixels wide. */
        void applyPackedInto(int[] src, int[] out, int stride, int ox, int oy) {
            if (dw == w && dh == h) {
                for (int y = 0; y < h; y++) {
                    System.arraycopy(src, y * w, out, (oy + y) * stride + ox, w);
                }
                return;
            }
            IntStream.range(0, dh).parallel().forEach(y -> rowPacked(src, out, y, (oy + y) * stride + ox));
        }

        private void rowPacked(int[] src, int[] out, int y, int o) {
            int top = y0[y] * w;
            int bot = y1[y] * w;
            int vy = wy[y];
            int uy = 256 - vy;
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

    /**
     * How the current picture is drawn (P3.4, P3.5). Pure logic, tested without a screen.
     *
     * <p>Fit: the frame is scaled to fit its area, centered, black around it. Side by side: the
     * window is split in two halves, decoded on the left and original on the right, each fitted
     * to its half. Zoom: no scaling, source pixels drawn 1:1 from the pan offset, so block
     * artifacts look exactly as large as they are (scaled 1080p hides them). In side by side,
     * both halves show the SAME region, so the two can be compared point by point. Overlay: the
     * decoded frame's foreground 8x8 blocks are tinted red, from the block types in the file.
     */
    static final class View {
        enum Toggle { SIDE_BY_SIDE, ZOOM, OVERLAY }

        /** Weight of the red tint out of 8: 3/8 red, 5/8 picture, so detail stays visible. */
        static final int TINT_EIGHTHS = 3;

        boolean sideBySide;
        boolean zoom;
        boolean overlay;
        int panX;
        int panY;
        private final Map<Long, Scaler> scalers = new HashMap<>();

        void toggle(Toggle t) {
            switch (t) {
                case SIDE_BY_SIDE -> sideBySide = !sideBySide;
                case ZOOM -> zoom = !zoom;
                case OVERLAY -> overlay = !overlay;
                default -> throw new IllegalArgumentException(t.toString());
            }
        }

        /** One line for the terminal after a view key, e.g. "side by side ON". */
        String describe(Toggle t, boolean hasRight) {
            return switch (t) {
                case SIDE_BY_SIDE -> !sideBySide ? "side by side OFF"
                        : hasRight ? "side by side ON"
                        : "side by side ON, but no original was given: run mydecoder file.cmp file.mp4";
                case ZOOM -> zoom ? "1:1 zoom ON (drag to pan)" : "1:1 zoom OFF";
                case OVERLAY -> overlay ? "foreground overlay ON" : "foreground overlay OFF";
            };
        }

        /** Short tags for the status line, e.g. " | side by side | 1:1". */
        String tags(boolean hasRight) {
            StringBuilder b = new StringBuilder();
            if (sideBySide) {
                b.append(hasRight ? " | side by side" : " | side by side needs the original .mp4");
            }
            if (zoom) {
                b.append(" | 1:1 (drag to pan)");
            }
            if (overlay) {
                b.append(" | foreground in red");
            }
            return b.toString();
        }

        /**
         * Draws into {@code out} (dw x dh). {@code left} is the decoded frame, already tinted if
         * the overlay is on; {@code right} the original or null. Clamps the pan offset.
         */
        void render(int[] left, int[] right, int w, int h, int[] out, int dw, int dh) {
            java.util.Arrays.fill(out, 0);
            boolean two = sideBySide && right != null;
            int areaW = two ? dw / 2 : dw;
            if (zoom) {
                panX = Math.max(0, Math.min(panX, w - areaW));
                panY = Math.max(0, Math.min(panY, h - dh));
                crop(left, w, h, out, dw, 0, areaW, dh);
                if (two) {
                    crop(right, w, h, out, dw, areaW, areaW, dh);
                }
            } else {
                fit(left, w, h, out, dw, 0, areaW, dh);
                if (two) {
                    fit(right, w, h, out, dw, areaW, areaW, dh);
                }
            }
        }

        private void fit(int[] src, int w, int h, int[] out, int stride, int ax, int aw, int ah) {
            double sc = Math.min((double) aw / w, (double) ah / h);
            int rw = Math.max(1, Math.min(aw, (int) Math.round(w * sc)));
            int rh = Math.max(1, Math.min(ah, (int) Math.round(h * sc)));
            long key = ((long) w << 48) | ((long) h << 32) | ((long) rw << 16) | rh;
            Scaler s = scalers.computeIfAbsent(key, k -> new Scaler(w, h, rw, rh));
            s.applyPackedInto(src, out, stride, ax + (aw - rw) / 2, (ah - rh) / 2);
        }

        /** 1:1 copy of the region at the pan offset; a source smaller than the area is centered. */
        private void crop(int[] src, int w, int h, int[] out, int stride, int ax, int aw, int ah) {
            int cw = Math.min(aw, w);
            int ch = Math.min(ah, h);
            int ox = ax + (aw - cw) / 2;
            int oy = (ah - ch) / 2;
            for (int y = 0; y < ch; y++) {
                System.arraycopy(src, (panY + y) * w + panX, out, (oy + y) * stride + ox, cw);
            }
        }

        /** Copies {@code src} into {@code dst}, tinting pixels whose 8x8 block type is 1. */
        static void tint(int[] src, byte[] types, int blocksX, int w, int h, int[] dst) {
            for (int y = 0; y < h; y++) {
                int rowBlocks = (y >> 3) * blocksX;
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    int p = src[i];
                    if (types[rowBlocks + (x >> 3)] == 1) {
                        int r = (((p >>> 16) & 0xFF) * (8 - TINT_EIGHTHS) + 255 * TINT_EIGHTHS) >> 3;
                        int g = (((p >>> 8) & 0xFF) * (8 - TINT_EIGHTHS)) >> 3;
                        int b = ((p & 0xFF) * (8 - TINT_EIGHTHS)) >> 3;
                        p = (r << 16) | (g << 8) | b;
                    }
                    dst[i] = p;
                }
            }
        }
    }

    /** Draws the display-sized image 1:1. The lock keeps a half-written frame from being drawn. */
    private static final class Canvas extends JPanel {
        private static final long serialVersionUID = 1L;
        private final transient Scaler scaler;
        private final transient BufferedImage image;
        private final transient int[] pixels;
        private final transient Object lock = new Object();
        private final transient View view = new View();
        /** Status line, written by the player thread, read on the event thread. */
        transient volatile String status;
        // Last layered frame, kept so a view key can redraw it while paused. Guarded by lock.
        private transient int[] left;
        private transient byte[] types;
        private transient int blocksX;
        private transient int[] right;
        private transient int srcW;
        private transient int srcH;
        private transient int[] tinted;

        Canvas(Scaler scaler) {
            this.scaler = scaler;
            this.image = new BufferedImage(scaler.dw, scaler.dh, BufferedImage.TYPE_INT_RGB);
            this.pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            setPreferredSize(new Dimension(scaler.dw, scaler.dh));
            setDoubleBuffered(true);
        }

        void load(Frame f) {
            synchronized (lock) {
                left = null; // plain frame path (ProbeVideo): view keys have nothing to redraw
                scaler.apply(f, pixels);
            }
        }

        void loadLayers(int[] l, byte[] t, int bx, int[] r, int w, int h) {
            synchronized (lock) {
                left = l;
                types = t;
                blocksX = bx;
                right = r;
                srcW = w;
                srcH = h;
                renderLocked();
            }
        }

        private void renderLocked() {
            int[] src = left;
            if (view.overlay && types != null) {
                if (tinted == null || tinted.length != left.length) {
                    tinted = new int[left.length];
                }
                View.tint(left, types, blocksX, srcW, srcH, tinted);
                src = tinted;
            }
            view.render(src, right, srcW, srcH, pixels, scaler.dw, scaler.dh);
        }

        /** Event thread: flips a view setting and redraws the current picture at once. */
        String toggle(View.Toggle t) {
            String line;
            synchronized (lock) {
                view.toggle(t);
                if (left != null) {
                    renderLocked();
                }
                line = view.describe(t, left == null || right != null);
            }
            repaint();
            return line;
        }

        /** Event thread: moves the 1:1 view by screen pixels (= source pixels when zoomed). */
        void pan(int dx, int dy) {
            synchronized (lock) {
                if (!view.zoom || left == null) {
                    return;
                }
                view.panX += dx;
                view.panY += dy;
                renderLocked();
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            String tags;
            synchronized (lock) {
                g.drawImage(image, 0, 0, null);
                tags = left == null ? "" : view.tags(right != null);
            }
            String s = status;
            if (s != null) {
                drawStatus(g, s + tags);
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
