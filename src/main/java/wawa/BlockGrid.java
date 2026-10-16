package wawa;

/**
 * The collision grid LoadGameBlock builds for a map: every cell's tile goes through
 * g_anTileBlock, the 64-byte table of its layer's tile set. CollHeroPath treats a cell with
 * either of the low two bits set as terrain the hero cannot enter (sea, cliffs, cave walls, lava,
 * rocks). LoadGameBlock also ORs in map object footprints as bit 0; those are left out because
 * this port does not place the objects yet, so they would be invisible walls.
 */
final class BlockGrid {

	/** g_anTileBlock: 64 bytes for each of the 14 tile sets. */
	private static final int TILE_BLOCK = 0x0ba85c;
	/** What LoadGameBlock reads for a layer whose tile set id is outside 1..14. */
	private static final int NO_TILE_SET = 0xcaadc;

	private final int width;
	private final int height;
	private final byte[] cells;

	BlockGrid(MapData map, ElfImage so) {
		width = map.width;
		height = map.height;
		cells = new byte[width * height];
		for (int i = 0; i < cells.length; i++) {
			int c = map.cells[i] & 0xff;
			int set = map.layerTileSet[c >> 6];
			int table = set >= 1 && set <= 14 ? TILE_BLOCK + (set - 1) * 64 : NO_TILE_SET;
			cells[i] = (byte) so.u8(table + (c & 0x3f));
		}
	}

	/** CollHeroPath's terrain test; a cell off the map counts as terrain. */
	boolean terrain(int x, int y) {
		return blocks(x, y, 3);
	}

	/** Whether the cell's tile has any of {@code mask}'s bits (CollMonsPath's mask); a cell off the map always does. */
	boolean blocks(int x, int y, int mask) {
		return x < 0 || y < 0 || x >= width || y >= height || (cells[y * width + x] & mask) != 0;
	}
}
