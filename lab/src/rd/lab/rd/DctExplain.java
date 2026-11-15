package lab.rd;

import codec.common.Dct;
import codec.common.Quant;
import codec.common.Types;
import codec.common.Types.Frame;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * One picture that explains part 2 of the brief: {@code DctExplain <clip> --at f [--out file.png]}.
 *
 * <p>Takes one foreground and one background 8x8 block from frame f (labels from the real
 * segmenter) and shows, per block: (a) the pixels, (b) the 64 DCT coefficients as a heat grid,
 * (c) and (d) the quantized values q = round(c / 2^n) for n = 2 and n = 5 with their zero counts,
 * (e) and (f) the blocks rebuilt from them. Uses the codec's own Dct and Quant, so the numbers are
 * exactly what myencoder stores.
 *
 * <p>Block choice is deterministic: among blocks fully inside the true frame (foreground: only the
 * largest region, i.e. the main subject), the one at the 90th percentile of G variance in each layer (textured, so quantization has something to remove, but
 * not the single most extreme edge). The grids show the G channel; R and B behave the same and are
 * included in the zero counts and the rebuilt colours.
 */
public final class DctExplain {

    static final String USAGE = "usage: DctExplain <clip> --at f [--out lab/out/rd/dct_<clip>_f<f>.png]";
    static final int[] NS = {2, 5};
    static final double PICK_PERCENTILE = 0.90;
    static final int CELL = 46;
    static final int GRID = 8 * CELL;
    static final int GAP = 28;
    static final int PANELS = 6;
    static final int THUMB_H = 300;

    // Reference palette (dataviz skill, light surface). Diverging blue <-> red, neutral grey midpoint.
    static final Color SURFACE = new Color(0xfcfcfb);
    static final Color INK = new Color(0x0b0b0b);
    static final Color INK2 = new Color(0x52514e);
    static final Color MID = new Color(0xf0efec);
    static final Color NEG = new Color(0x1c5cab);
    static final Color POS = new Color(0xb4302f);
    static final Color FG_MARK = new Color(0xeb6834);
    static final Color BG_MARK = new Color(0x2a78d6);

    private DctExplain() {
    }

    /** One 8x8 block of all three channels: pixels, coefficients, quantized and rebuilt per n. */
    record Block(String name, int x0, int y0, double variance, int[][] px, double[][] coef,
                 short[][][] q, int[][][] rebuilt) {
    }

    public static void main(String[] a) throws Exception {
        if (a.length < 3 || !a[1].equals("--at")) {
            System.err.println(USAGE);
            System.exit(2);
        }
        Path clip = Path.of(a[0]);
        int f = Integer.parseInt(a[2]);
        Path out = a.length >= 5 && a[3].equals("--out") ? Path.of(a[4])
                : Path.of(String.format(Locale.ROOT, "lab/out/rd/dct_%s_f%d.png", RdCore.stem(clip), f));
        // Labels for frame f need frames f-1 and f+1 (temporal vote), so load a short window around it.
        int start = Math.max(0, f - 3);
        RdCore.Segment seg = RdCore.load(clip, start, f - start + 3, false);
        int k = f - start;
        if (k >= seg.frames()) {
            throw new IOException("clip has fewer than " + (f + 1) + " frames");
        }
        Frame p = seg.padded(k);
        Types.Labels lab = seg.labels(k);
        Block fg = pick(p, lab, seg.info, true);
        Block bg = pick(p, lab, seg.info, false);
        if (fg == null || bg == null) {
            throw new IOException("frame " + f + " has no " + (fg == null ? "foreground" : "background")
                    + " block; try another --at");
        }
        Files.createDirectories(out.toAbsolutePath().getParent());
        ImageIO.write(draw(p, seg.info, RdCore.stem(clip), f, fg, bg), "png", out.toFile());
        for (Block b : new Block[] {fg, bg}) {
            System.out.printf(Locale.ROOT, "%s block at (%d,%d): G variance %.0f, DC(G) %.1f, zeros n=2 %d/192, n=5 %d/192,"
                    + " block PSNR n=2 %.2f dB, n=5 %.2f dB%n", b.name, b.x0, b.y0, b.variance, b.coef[1][0],
                    zeros(b.q[0], -1), zeros(b.q[1], -1), psnr(b.px, b.rebuilt[0]), psnr(b.px, b.rebuilt[1]));
        }
        System.out.println("wrote " + out);
    }

    /** The 90th-percentile-variance block of one layer, or null if the layer is empty. */
    static Block pick(Frame p, Types.Labels lab, Types.VideoInfo info, boolean foreground) {
        int bx = info.width() / 8;
        int by = info.height() / 8; // only blocks fully inside the true frame (no padding rows)
        int region = largestRegion(lab);
        java.util.List<double[]> cand = new java.util.ArrayList<>();
        for (int y = 0; y < by; y++) {
            for (int x = 0; x < bx; x++) {
                int id = lab.get(x / 2, y / 2);
                // Foreground: only the largest region (the main subject), not small leftover patches.
                if (foreground ? id != 0 && id == region : id == 0) {
                    cand.add(new double[] {variance(p.g(), p.width(), x * 8, y * 8), x, y});
                }
            }
        }
        if (cand.isEmpty()) {
            return null;
        }
        // Sort by variance, ties by scan order, so the pick never depends on anything but the frame.
        cand.sort((u, v) -> u[0] != v[0] ? Double.compare(u[0], v[0])
                : Double.compare(u[2] * 1e6 + u[1], v[2] * 1e6 + v[1]));
        double[] c = cand.get((int) Math.min(cand.size() - 1, Math.floor(PICK_PERCENTILE * cand.size())));
        return analyse(p, foreground ? "Foreground" : "Background", (int) c[1] * 8, (int) c[2] * 8, c[0]);
    }

    /** Region id (1..255) with the most macroblocks, smallest id on ties; 0 if none. */
    static int largestRegion(Types.Labels lab) {
        int[] count = new int[256];
        for (int r = 0; r < lab.rows(); r++) {
            for (int c = 0; c < lab.cols(); c++) {
                count[lab.get(c, r)]++;
            }
        }
        int best = 0;
        for (int id = 1; id < 256; id++) {
            if (count[id] > 0 && (best == 0 || count[id] > count[best])) {
                best = id;
            }
        }
        return best;
    }

    static double variance(int[] plane, int stride, int x0, int y0) {
        double s = 0;
        double s2 = 0;
        for (int i = 0; i < 64; i++) {
            int v = plane[(y0 + i / 8) * stride + x0 + i % 8];
            s += v;
            s2 += (double) v * v;
        }
        return s2 / 64 - (s / 64) * (s / 64);
    }

    static Block analyse(Frame p, String name, int x0, int y0, double var) {
        int[][] planes = {p.r(), p.g(), p.b()};
        int[][] px = new int[3][64];
        double[][] coef = new double[3][64];
        short[][][] q = new short[NS.length][3][64];
        int[][][] rebuilt = new int[NS.length][3][64];
        double[] tmp = new double[64];
        double[] deq = new double[64];
        double[] back = new double[64];
        for (int ch = 0; ch < 3; ch++) {
            for (int i = 0; i < 64; i++) {
                px[ch][i] = planes[ch][(y0 + i / 8) * p.width() + x0 + i % 8];
            }
            Dct.forward(planes[ch], p.width(), x0, y0, coef[ch], tmp);
            for (int j = 0; j < NS.length; j++) {
                Quant.quantizeBlock(coef[ch], NS[j], q[j][ch], 0);
                Quant.dequantizeBlock(q[j][ch], 0, NS[j], deq);
                Dct.inverse(deq, back, tmp);
                for (int i = 0; i < 64; i++) {
                    rebuilt[j][ch][i] = Quant.toPixel(back[i]);
                }
            }
        }
        return new Block(name, x0, y0, var, px, coef, q, rebuilt);
    }

    /** Zeros in one channel (ch 0..2) or in all three (ch = -1). */
    static int zeros(short[][] q, int ch) {
        int z = 0;
        for (int c = 0; c < 3; c++) {
            if (ch >= 0 && c != ch) {
                continue;
            }
            for (short v : q[c]) {
                z += v == 0 ? 1 : 0;
            }
        }
        return z;
    }

    static double psnr(int[][] a, int[][] b) {
        long sse = 0;
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < 64; i++) {
                int d = a[c][i] - b[c][i];
                sse += (long) d * d;
            }
        }
        return codec.tools.Psnr.psnr(sse, 192);
    }

    // ---------------------------------------------------------------- drawing

    static BufferedImage draw(Frame p, Types.VideoInfo info, String clip, int f, Block fg, Block bg) {
        int width = PANELS * GRID + (PANELS + 1) * GAP;
        int rowH = 64 + GRID + 40;
        int height = 70 + THUMB_H + 30 + 2 * rowH + 60;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(SURFACE);
        g.fillRect(0, 0, width, height);
        text(g, String.format(Locale.ROOT, "%s, frame %d: what the 8x8 DCT and the 2^n quantizer do to one foreground"
                + " and one background block", clip, f), GAP, 40, 26, Font.BOLD, INK);
        drawThumb(g, p, info, fg, bg, GAP, 70);
        double scale = 0;
        for (Block b : new Block[] {fg, bg}) {
            for (double v : b.coef[1]) {
                scale = Math.max(scale, Math.abs(v));
            }
        }
        int y = 70 + THUMB_H + 30;
        for (Block b : new Block[] {fg, bg}) {
            drawRow(g, b, b == fg ? FG_MARK : BG_MARK, y, scale);
            y += rowH;
        }
        text(g, "The DCT itself loses nothing (rebuilding from unquantized coefficients returns the pixels). Dividing by 2^n"
                + " and rounding is the only loss: bigger n, more zeros, fewer bits, more blur.", GAP, y + 10, 17, Font.PLAIN, INK2);
        text(g, "In the codec the foreground uses n1 and the background n2; (2,5) means the foreground gets column (c) and"
                + " the background column (d). Grids show G; zero counts and colours use R, G and B.", GAP, y + 36, 17, Font.PLAIN, INK2);
        g.dispose();
        return img;
    }

    static void drawThumb(Graphics2D g, Frame p, Types.VideoInfo info, Block fg, Block bg, int x, int y) {
        double s = (double) THUMB_H / info.height();
        int tw = (int) Math.round(info.width() * s);
        BufferedImage t = new BufferedImage(tw, THUMB_H, BufferedImage.TYPE_INT_RGB);
        for (int ty = 0; ty < THUMB_H; ty++) {
            for (int tx = 0; tx < tw; tx++) {
                int i = Math.min(info.height() - 1, (int) (ty / s)) * p.width() + Math.min(info.width() - 1, (int) (tx / s));
                t.setRGB(tx, ty, (p.r()[i] << 16) | (p.g()[i] << 8) | p.b()[i]);
            }
        }
        g.drawImage(t, x, y, null);
        g.setStroke(new BasicStroke(3f));
        int lx = x + tw + 30;
        int ly = y + 40;
        for (Block b : new Block[] {fg, bg}) {
            Color c = b == fg ? FG_MARK : BG_MARK;
            int bx = x + (int) Math.round(b.x0 * s);
            int by = y + (int) Math.round(b.y0 * s);
            int side = Math.max(12, (int) Math.round(8 * s));
            g.setColor(SURFACE);
            g.drawRect(bx - 4, by - 4, side + 8, side + 8);
            g.setColor(c);
            g.drawRect(bx - 2, by - 2, side + 4, side + 4);
            g.fillRect(lx, ly - 14, 18, 18);
            text(g, String.format(Locale.ROOT, "%s block at pixel (%d, %d), G variance %.0f", b.name, b.x0, b.y0,
                    b.variance), lx + 28, ly, 19, Font.PLAIN, INK);
            ly += 34;
        }
        text(g, "Labels come from the real segmenter (motion only). Picked automatically: foreground from the largest region,",
                lx, ly + 20, 17, Font.PLAIN, INK2);
        text(g, "each at the 90th-percentile G variance of its layer (textured, not an extreme edge).",
                lx, ly + 44, 17, Font.PLAIN, INK2);
    }

    static void drawRow(Graphics2D g, Block b, Color mark, int y, double scale) {
        g.setColor(mark);
        g.fillRect(GAP, y + 2, 18, 18);
        text(g, String.format(Locale.ROOT, "%s block (%d, %d)", b.name, b.x0, b.y0), GAP + 28, y + 18, 21, Font.BOLD, INK);
        String[] titles = {
            "(a) pixels, G value printed",
            "(b) DCT of G, DC top left",
            String.format(Locale.ROOT, "(c) q = round(c / 4), n=2: %d/64 zero", zeros(b.q[0], 1)),
            String.format(Locale.ROOT, "(d) q = round(c / 32), n=5: %d/64 zero", zeros(b.q[1], 1)),
            String.format(Locale.ROOT, "(e) rebuilt, n=2: %.1f dB", psnr(b.px, b.rebuilt[0])),
            String.format(Locale.ROOT, "(f) rebuilt, n=5: %.1f dB", psnr(b.px, b.rebuilt[1])),
        };
        String[] subs = {
            "original colours",
            String.format(Locale.ROOT, "colour: sign and size, scale +/-%.0f", scale),
            String.format(Locale.ROOT, "RGB: %d/192 zero; colour = q x 4", zeros(b.q[0], -1)),
            String.format(Locale.ROOT, "RGB: %d/192 zero; colour = q x 32", zeros(b.q[1], -1)),
            "block PSNR vs (a), R G B",
            "block PSNR vs (a), R G B",
        };
        int gy = y + 64;
        for (int panel = 0; panel < PANELS; panel++) {
            int gx = GAP + panel * (GRID + GAP);
            text(g, titles[panel], gx, y + 42, 16, Font.BOLD, INK);
            text(g, subs[panel], gx, y + 60, 14, Font.PLAIN, INK2);
            for (int i = 0; i < 64; i++) {
                int cx = gx + (i % 8) * CELL;
                int cy = gy + (i / 8) * CELL;
                Color fill;
                String label;
                switch (panel) {
                    case 0 -> {
                        fill = rgb(b.px, i);
                        label = Integer.toString(b.px[1][i]);
                    }
                    case 1 -> {
                        fill = diverging(b.coef[1][i], scale);
                        label = Long.toString(Math.round(b.coef[1][i]));
                    }
                    case 2, 3 -> {
                        int j = panel - 2;
                        short v = b.q[j][1][i];
                        fill = v == 0 ? MID : diverging(v * (double) (1 << NS[j]), scale);
                        label = v == 0 ? "0" : Short.toString(v);
                    }
                    default -> {
                        fill = rgb(b.rebuilt[panel - 4], i);
                        label = Integer.toString(b.rebuilt[panel - 4][1][i]);
                    }
                }
                g.setColor(fill);
                g.fillRect(cx + 1, cy + 1, CELL - 2, CELL - 2); // 2px surface gap between cells
                boolean zero = (panel == 2 || panel == 3) && label.equals("0");
                Color ink = zero ? new Color(0xa8a7a2) : (luminance(fill) < 0.45 ? Color.WHITE : INK);
                centered(g, label, cx + CELL / 2, cy + CELL / 2, label.length() > 4 ? 11 : 13, ink);
            }
        }
    }

    static Color rgb(int[][] px, int i) {
        return new Color(px[0][i], px[1][i], px[2][i]);
    }

    /** Signed value to the blue-grey-red ramp; square root so small AC terms stay visible next to DC. */
    static Color diverging(double v, double scale) {
        double t = Math.min(1, Math.sqrt(Math.abs(v) / scale));
        Color pole = v < 0 ? NEG : POS;
        return new Color(mix(MID.getRed(), pole.getRed(), t), mix(MID.getGreen(), pole.getGreen(), t),
                mix(MID.getBlue(), pole.getBlue(), t));
    }

    static int mix(int a, int b, double t) {
        return (int) Math.round(a + (b - a) * t);
    }

    static double luminance(Color c) {
        return (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255.0;
    }

    static void text(Graphics2D g, String s, int x, int y, int size, int style, Color c) {
        g.setFont(new Font(Font.SANS_SERIF, style, size));
        g.setColor(c);
        g.drawString(s, x, y);
    }

    static void centered(Graphics2D g, String s, int cx, int cy, int size, Color c) {
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, size));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(c);
        g.drawString(s, cx - fm.stringWidth(s) / 2, cy + (fm.getAscent() - fm.getDescent()) / 2);
    }
}
