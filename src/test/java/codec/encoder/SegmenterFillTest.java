package codec.encoder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import codec.common.Types.Labels;
import codec.encoder.Segmenter.SegConfig;
import org.junit.jupiter.api.Test;

class SegmenterFillTest {

    /** Rows of digits, one char per block. */
    private static Labels grid(String... rows) {
        int cols = rows[0].length();
        byte[] id = new byte[cols * rows.length];
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < cols; x++) {
                id[y * cols + x] = (byte) (rows[y].charAt(x) - '0');
            }
        }
        return new Labels(cols, rows.length, id);
    }

    private static String[] text(Labels l) {
        String[] out = new String[l.rows()];
        for (int y = 0; y < l.rows(); y++) {
            StringBuilder sb = new StringBuilder();
            for (int x = 0; x < l.cols(); x++) {
                sb.append(l.get(x, y));
            }
            out[y] = sb.toString();
        }
        return out;
    }

    @Test
    void enclosedHoleInsideOneRegionIsFilled() {
        Labels in = grid(
                "0000000",
                "0111110",
                "0100010",
                "0100010",
                "0111110",
                "0000000");
        assertArrayEquals(new String[] {
                "0000000",
                "0111110",
                "0111110",
                "0111110",
                "0111110",
                "0000000"}, text(Segmenter.fill(in, SegConfig.DEFAULT)));
    }

    @Test
    void notchWithThreeSameNeighboursJoinsButOpenGapDoesNot() {
        Labels in = grid(
                "000000",
                "011100",
                "010100",
                "000000");
        // (2,2) has neighbours 1 (left), 1 (right), 1 (top), 0 (below): joins. (2,3) touches only one 1.
        Labels out = Segmenter.fill(in, SegConfig.DEFAULT);
        assertEquals(1, out.get(2, 2));
        assertEquals(0, out.get(2, 3));
    }

    @Test
    void pocketBetweenTwoRegionsOrTouchingBorderStaysBackground() {
        Labels in = grid(
                "1111222",
                "1000002",
                "1111222");
        Labels out = Segmenter.fill(in, SegConfig.DEFAULT);
        assertEquals(0, out.get(3, 1), "bordered by two regions");
        Labels open = grid(
                "011110",
                "010010",
                "010010");
        // The pocket reaches the bottom border: not enclosed.
        assertEquals(0, Segmenter.fill(open, SegConfig.DEFAULT).get(2, 2));
    }

    @Test
    void holeLargerThanItsRegionIsNotFilled() {
        // A thin ring around a big empty area: probably two separate things, not one solid object.
        Labels in = grid(
                "000000000",
                "011111110",
                "010000010",
                "010000010",
                "010000010",
                "010000010",
                "011111110",
                "000000000");
        Labels out = Segmenter.fill(in, SegConfig.DEFAULT);
        // Ring has 24 blocks, pocket 20: filled. Shrink the ring's share by checking the rule directly.
        assertEquals(1, out.get(4, 3));
        Labels big = grid(
                "0000000000000",
                "0111111111110",
                "0100000000010",
                "0100000000010",
                "0100000000010",
                "0100000000010",
                "0100000000010",
                "0111111111110",
                "0000000000000");
        // Ring 32 blocks, pocket 45 blocks: left alone.
        assertEquals(0, Segmenter.fill(big, SegConfig.DEFAULT).get(5, 4));
    }

    @Test
    void dilationGrowsByOneBlockAndLowerIdWinsTies() {
        SegConfig d = SegConfig.DEFAULT;
        SegConfig dil = new SegConfig(d.minTextureVar(), d.maxMatchMad(), d.candMinVar(), d.camSlackMad(), d.camSlackRel(),
                d.camWindow(), d.resPx(), d.consPx(), d.consMinMag(), d.consAngleDeg(), d.minRegionBlocks(),
                d.minRegionFrac(), d.maxSpreadPx(), true);
        Labels in = grid(
                "00000",
                "01020",
                "00000");
        assertArrayEquals(new String[] {
                "01020",
                "11122",
                "01020"}, text(Segmenter.fill(in, dil)));
    }

    @Test
    void inputIsNotModified() {
        Labels in = grid("010", "101", "010");
        byte[] before = in.id().clone();
        Segmenter.fill(in, SegConfig.DEFAULT);
        assertArrayEquals(before, in.id());
    }
}
