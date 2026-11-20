package diag;
import codec.common.*; import codec.common.Types.*; import codec.encoder.*;
import java.nio.file.*;
public class C1Check {
  public static void main(String[] a) throws Exception {
    try (CmpFormat.Reader r = new CmpFormat.Reader(Path.of(a[0])); LabelsIO.Reader l = new LabelsIO.Reader(Path.of(a[1]))) {
      CmpFormat.Header h = r.header();
      int bx = h.paddedW() / 8, by = h.paddedH() / 8;
      byte[] types = new byte[h.blockCount()]; short[] coef = new short[h.blockCount() * CmpFormat.COEF_PER_BLOCK];
      long blocks = 0, mismatch = 0, fg = 0;
      if (r.frameCount() != l.frames()) { System.out.println("FRAME COUNT DIFFERS " + r.frameCount() + " vs " + l.frames()); return; }
      for (int k = 0; k < r.frameCount(); k++) {
        r.readFrame(k, types, coef); Labels lab = l.read(k);
        for (int y = 0; y < by; y++) for (int x = 0; x < bx; x++) {
          int t = types[y * bx + x]; int want = lab.get(x / 2, y / 2) != 0 ? 1 : 0;
          blocks++; fg += t; if (t != want) mismatch++;
        }
      }
      System.out.printf("%s frames %d blocks %d mismatches %d foreground 8x8 %.2f%%%n", a[0], r.frameCount(), blocks, mismatch, 100.0 * fg / blocks);
    }
  }
}
