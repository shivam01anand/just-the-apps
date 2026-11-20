package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class WallDataTest {

    private static List<WallData.Tile> tiles(int n, int bigs) {
        List<WallData.Tile> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            WallData.Tile t = new WallData.Tile();
            t.id = "tt" + i;
            t.big = i % (n / Math.max(1, bigs)) == 0 && bigs-- > 0;
            out.add(t);
        }
        return out;
    }

    /** Every tile hangs once, big ones over exactly their four spaces, and no column is left empty. */
    private static void check(List<WallData.Tile> tiles, int rows, boolean sizes) {
        WallData.Hang h = WallData.hang(tiles, rows, sizes);
        int spaces = 0;
        for (WallData.Tile t : tiles) {
            assertTrue(t.w == 1 || (sizes && t.big && t.w == 2));
            for (int x = 0; x < t.w; x++) for (int y = 0; y < t.w; y++) assertSame(t, h.at(t.c + x, t.r + y));
            spaces += t.w * t.w;
        }
        int filled = 0;
        for (WallData.Tile[] col : h.cells) {
            int inCol = 0;
            for (WallData.Tile t : col) if (t != null) inCol++;
            assertTrue(inCol > 0);
            filled += inCol;
        }
        assertEquals(spaces, filled);
    }

    @Test
    public void hangsEveryTileOnce() {
        check(tiles(239, 27), 8, true);
        check(tiles(100, 11), 6, true);
        check(tiles(29, 4), 4, true);
        check(tiles(12, 0), 2, false);
        check(tiles(1, 0), 4, true);
        check(tiles(5, 5), 4, true);
    }

    @Test
    public void bigTilesAreSpreadAlongTheWall() {
        List<WallData.Tile> tiles = tiles(239, 27);
        WallData.Hang h = WallData.hang(tiles, 8, true);
        int firstHalf = 0, big = 0;
        for (WallData.Tile t : tiles) {
            if (t.w != 2) continue;
            big++;
            if (t.c < h.cells.length / 2) firstHalf++;
        }
        assertTrue(big >= 24);
        assertTrue(firstHalf >= big / 3 && firstHalf <= big * 2 / 3);
    }

    @Test
    public void sizesOffHangsEverythingSmall() {
        List<WallData.Tile> tiles = tiles(12, 3);
        WallData.Hang h = WallData.hang(tiles, 2, false);
        assertEquals(6, h.cells.length);
        for (WallData.Tile t : tiles) assertEquals(1, t.w);
        assertNotNull(h.at(5, 1));
    }
}
